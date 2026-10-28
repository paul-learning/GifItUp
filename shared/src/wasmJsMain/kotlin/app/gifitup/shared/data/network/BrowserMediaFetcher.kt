package app.gifitup.shared.data.network

import app.gifitup.shared.data.content.GifReference
import app.gifitup.shared.domain.media.FetchResult
import app.gifitup.shared.domain.media.MediaFetcher
import app.gifitup.shared.domain.media.MediaScanner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.await
import kotlin.js.JsAny
import kotlin.js.Promise

/**
 * [MediaFetcher] for the web (ADR-0003, GIF spike): the browser's own `fetch()` with the options a browser needs to
 * keep the promises of the Android fetcher, because Ktor's engine can't be trusted to set them:
 * - `credentials: "omit"`: no cookies or other credentials;
 * - `referrerPolicy: "no-referrer"`: no `Referer`;
 * - `redirect: "error"`: a redirect fails the request (a browser can't show us the target, so "follow if it passes
 *   the check" can't be done);
 * - `mode: "cors"`, the `Accept` header of the Android fetcher, and nothing else.
 * Only a complete `200` counts; the body is read as a stream and stops at [MediaScanner.MAX_BYTES], the declared
 * length is checked first, the rest of an unwanted body is cancelled, and the whole call is aborted after 30 seconds.
 * The bytes are then shown from a `blob:` URL ([BrowserBlobUrl]), never through an `<img>` that points at the provider.
 */
class BrowserMediaFetcher : MediaFetcher {
    override suspend fun fetch(reference: GifReference): FetchResult = BrowserFetch.fetchCapped(reference.url)
}

/** The `fetch()` call itself, apart from the [GifReference] check so a test can point it at a local server. */
internal object BrowserFetch {
    private const val TOO_LARGE = 2
    private const val TIMEOUT_MS = 30_000

    @Suppress("TooGenericExceptionCaught") // The browser throws its own types (TypeError, AbortError); all mean "failed".
    suspend fun fetchCapped(
        url: String,
        maxBytes: Int = MediaScanner.MAX_BYTES,
        timeoutMs: Int = TIMEOUT_MS,
    ): FetchResult =
        try {
            val outcome = fetchOutcome(url, maxBytes, timeoutMs).await<JsAny>()
            when (outcomeStatus(outcome)) {
                0 -> FetchResult.Bytes(outcomeData(outcome).toByteArray())
                TOO_LARGE -> FetchResult.TooLarge
                else -> FetchResult.Failed
            }
        } catch (e: CancellationException) {
            throw e
        } catch (ignored: Throwable) {
            FetchResult.Failed
        }
}

private fun JsBytes.toByteArray(): ByteArray = ByteArray(jsLength(this)) { jsByteAt(this, it).toByte() }

// The result is an object {status, data}: 0 with the bytes, 1 failed, 2 too large. Never throws (the promise resolves).
@JsFun(
    """
(url, maxBytes, timeoutMs) => {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  const done = (status, data) => ({ status: status, data: data });
  return fetch(url, {
    method: 'GET',
    credentials: 'omit',
    referrerPolicy: 'no-referrer',
    redirect: 'error',
    mode: 'cors',
    cache: 'default',
    headers: { 'Accept': 'image/webp,image/gif' },
    signal: controller.signal,
  }).then(async (response) => {
    if (response.status !== 200 || response.type === 'opaque' || response.type === 'opaqueredirect') {
      controller.abort();
      return done(1, null);
    }
    const declared = response.headers.get('content-length');
    if (declared !== null && Number(declared) > maxBytes) {
      controller.abort();
      return done(2, null);
    }
    if (response.body === null) return done(1, null);
    const reader = response.body.getReader();
    const chunks = [];
    let total = 0;
    for (;;) {
      const next = await reader.read();
      if (next.done) break;
      total += next.value.length;
      if (total > maxBytes) {
        controller.abort();
        return done(2, null);
      }
      chunks.push(next.value);
    }
    const bytes = new Uint8Array(total);
    let offset = 0;
    for (const chunk of chunks) {
      bytes.set(chunk, offset);
      offset += chunk.length;
    }
    return done(0, bytes);
  }).catch(() => done(1, null)).finally(() => clearTimeout(timer));
}
""",
)
private external fun fetchOutcome(
    url: String,
    maxBytes: Int,
    timeoutMs: Int,
): Promise<JsAny>

@JsFun("(outcome) => outcome.status")
private external fun outcomeStatus(outcome: JsAny): Int

@JsFun("(outcome) => outcome.data")
private external fun outcomeData(outcome: JsAny): JsBytes
