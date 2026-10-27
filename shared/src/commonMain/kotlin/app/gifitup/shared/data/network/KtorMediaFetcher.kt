package app.gifitup.shared.data.network

import app.gifitup.shared.data.content.GifReference
import app.gifitup.shared.domain.media.FetchResult
import app.gifitup.shared.domain.media.MediaFetcher
import app.gifitup.shared.domain.media.MediaScanner
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentLength
import io.ktor.utils.io.cancel
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CancellationException
import kotlinx.io.readByteArray

/**
 * [MediaFetcher] over Ktor, for Android and the JVM. **Not for the web:** a browser follows redirects by itself and
 * decides about credentials, so the web fetches with its own `fetch()` options (#24 part 4) and the app wires this class
 * on Android only. One
 * `GET` to the URL of a [GifReference], which already passed the Mode B′ checks (host allow-list, no query, no port):
 * - no cookie storage is installed and no `Referer` or app-specific header is set (the engine's own default headers, such
 *   as its `User-Agent`, are sent, which the provider sees anyway: ADR-0003);
 * - redirects are **not** followed (a 3xx is a failure);
 * - only a complete `200` counts, the declared length and the bytes that arrive are both capped at
 *   [MediaScanner.MAX_BYTES];
 * - any failure, including a platform `Error`, is the closed [FetchResult.Failed].
 * The content type is not trusted; the scanner sniffs the bytes.
 */
class KtorMediaFetcher(
    engine: HttpClientEngine,
) : MediaFetcher,
    AutoCloseable {
    private val client =
        HttpClient(engine) {
            expectSuccess = false
            followRedirects = false
            install(HttpTimeout) {
                connectTimeoutMillis = CONNECT_TIMEOUT_MS
                socketTimeoutMillis = SOCKET_TIMEOUT_MS
                requestTimeoutMillis = REQUEST_TIMEOUT_MS
            }
        }

    @Suppress("TooGenericExceptionCaught")
    override suspend fun fetch(reference: GifReference): FetchResult {
        var decided: FetchResult? = null
        return try {
            // execute { } hands over the response as it arrives; a plain get() would buffer the whole body first.
            client.prepareGet(reference.url) { header(HttpHeaders.Accept, ACCEPT) }.execute { response ->
                val result = answerOf(response).also { decided = it }
                // The rest of a body we don't want is dropped now; otherwise execute { } waits for it, up to the timeout.
                if (result !is FetchResult.Bytes) response.bodyAsChannel().cancel(null)
                result
            }
        } catch (e: CancellationException) {
            throw e
        } catch (ignored: Throwable) {
            // Ktor's fetch engine throws kotlin.Error, and every platform has its own network exceptions. Dropping the
            // rest of the body can end the call with the cancellation's error; the decision made before it stands.
            decided ?: FetchResult.Failed
        }
    }

    private suspend fun answerOf(response: HttpResponse): FetchResult {
        val declared = response.contentLength() ?: 0
        return when {
            response.status != HttpStatusCode.OK -> FetchResult.Failed
            declared > MediaScanner.MAX_BYTES -> FetchResult.TooLarge
            else -> readCapped(response)
        }
    }

    /** Reads at most [MediaScanner.MAX_BYTES] + 1 bytes off the wire: one more than the cap is enough to refuse. */
    private suspend fun readCapped(response: HttpResponse): FetchResult {
        val body = response.bodyAsChannel().readRemaining(MediaScanner.MAX_BYTES + 1L).readByteArray()
        return if (body.size > MediaScanner.MAX_BYTES) FetchResult.TooLarge else FetchResult.Bytes(body)
    }

    override fun close() = client.close()

    private companion object {
        const val ACCEPT = "image/webp,image/gif"
        const val CONNECT_TIMEOUT_MS = 10_000L
        const val SOCKET_TIMEOUT_MS = 15_000L
        const val REQUEST_TIMEOUT_MS = 30_000L
    }
}
