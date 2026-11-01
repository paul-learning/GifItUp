package app.gifitup.shared.data.gif

import app.gifitup.shared.data.network.JsBytes
import app.gifitup.shared.data.network.toByteArray
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.await
import kotlin.js.JsAny
import kotlin.js.Promise

/**
 * [ProviderFetcher] for the web: the browser's own `fetch()` with the options that keep the promises of the Ktor one,
 * because the key is in the URL and a followed redirect would carry it to another host (ADR-0003, #23 plan review SEC-1):
 * `credentials: "omit"`, `referrerPolicy: "no-referrer"`, `redirect: "error"` (a redirect fails the request), `mode: "cors"`,
 * `cache: "no-store"` (an API answer is not kept) and only an `Accept` header. What the browser adds itself is not ours to
 * remove: `Origin` (the web app's origin, which CORS requires), `User-Agent`, the language and fetch-metadata headers, so
 * Klipy learns the app's origin from every call (threat model). The body is read as a stream and stops at the cap, and the
 * whole call is aborted after [TIMEOUT_MS]. Never the Ktor JS engine for this request.
 */
class BrowserProviderFetcher : ProviderFetcher {
    override suspend fun get(
        url: String,
        maxBytes: Int,
    ): ProviderAnswer = BrowserApiFetch.fetch(url, maxBytes, TIMEOUT_MS)

    private companion object {
        const val TIMEOUT_MS = 30_000
    }
}

internal object BrowserApiFetch {
    private const val TOO_LARGE = 2

    @Suppress("TooGenericExceptionCaught") // The browser throws its own types; all mean "failed", and none is kept.
    suspend fun fetch(
        url: String,
        maxBytes: Int,
        timeoutMs: Int,
    ): ProviderAnswer =
        try {
            val call = startApiFetch(url, maxBytes, timeoutMs)
            val outcome =
                try {
                    apiOutcome(call).await<JsAny>()
                } catch (e: CancellationException) {
                    abortApiFetch(call)
                    throw e
                }
            when (apiOutcomeKind(outcome)) {
                0 -> ProviderAnswer.Reply(apiStatus(outcome), apiData(outcome).toByteArray(), apiRetryAfter(outcome).takeIf { it >= 0 })
                TOO_LARGE -> ProviderAnswer.TooLarge
                else -> ProviderAnswer.Failed
            }
        } catch (e: CancellationException) {
            throw e
        } catch (ignored: Throwable) {
            ProviderAnswer.Failed
        }
}

// The result is {kind, status, retryAfter, data}: kind 0 an answer (data empty unless status 200), 1 failed, 2 too large.
@JsFun(
    """
(url, maxBytes, timeoutMs) => {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  const done = (kind, status, retryAfter, data) => ({ kind: kind, status: status, retryAfter: retryAfter, data: data });
  const outcome = fetch(url, {
    method: 'GET',
    credentials: 'omit',
    referrerPolicy: 'no-referrer',
    redirect: 'error',
    mode: 'cors',
    cache: 'no-store',
    headers: { 'Accept': 'application/json' },
    signal: controller.signal,
  }).then(async (response) => {
    if (response.type === 'opaque' || response.type === 'opaqueredirect') {
      controller.abort();
      return done(1, 0, -1, null);
    }
    const header = response.headers.get('retry-after');
    const retryAfter = header !== null && /^[0-9]{1,4}$/.test(header) && Number(header) <= 3600 ? Number(header) : -1;
    if (response.status !== 200) {
      controller.abort();
      return done(0, response.status, retryAfter, new Uint8Array(0));
    }
    const declared = response.headers.get('content-length');
    if (declared !== null && Number(declared) > maxBytes) {
      controller.abort();
      return done(2, 0, -1, null);
    }
    if (response.body === null) return done(1, 0, -1, null);
    const reader = response.body.getReader();
    const chunks = [];
    let total = 0;
    for (;;) {
      const next = await reader.read();
      if (next.done) break;
      total += next.value.length;
      if (total > maxBytes) {
        controller.abort();
        return done(2, 0, -1, null);
      }
      chunks.push(next.value);
    }
    const bytes = new Uint8Array(total);
    let offset = 0;
    for (const chunk of chunks) {
      bytes.set(chunk, offset);
      offset += chunk.length;
    }
    return done(0, 200, retryAfter, bytes);
  }).catch(() => done(1, 0, -1, null)).finally(() => clearTimeout(timer));
  return { outcome: outcome, controller: controller };
}
""",
)
private external fun startApiFetch(
    url: String,
    maxBytes: Int,
    timeoutMs: Int,
): JsAny

@JsFun("(call) => call.outcome")
private external fun apiOutcome(call: JsAny): Promise<JsAny>

@JsFun("(call) => call.controller.abort()")
private external fun abortApiFetch(call: JsAny)

@JsFun("(outcome) => outcome.kind")
private external fun apiOutcomeKind(outcome: JsAny): Int

@JsFun("(outcome) => outcome.status")
private external fun apiStatus(outcome: JsAny): Int

@JsFun("(outcome) => outcome.retryAfter")
private external fun apiRetryAfter(outcome: JsAny): Int

@JsFun("(outcome) => outcome.data")
private external fun apiData(outcome: JsAny): JsBytes
