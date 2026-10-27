package app.gifitup.shared.data.network

import app.gifitup.shared.data.content.GifReference
import app.gifitup.shared.domain.media.FetchResult
import app.gifitup.shared.domain.media.MediaFetcher
import app.gifitup.shared.domain.media.MediaScanner
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentLength
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CancellationException
import kotlinx.io.readByteArray

/**
 * [MediaFetcher] over Ktor, for Android and the JVM (the web fetches with the browser's `fetch()`, #24 part 4). One
 * `GET` to the URL of a [GifReference], which already passed the Mode B′ checks (host allow-list, no query, no port):
 * - no cookie storage is installed and no `Referer` or other identifying header is set;
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

    @Suppress("TooGenericExceptionCaught") // Ktor's fetch engine throws kotlin.Error; every failure is the same answer.
    override suspend fun fetch(reference: GifReference): FetchResult =
        try {
            val response = client.get(reference.url) { header(HttpHeaders.Accept, ACCEPT) }
            val declared = response.contentLength() ?: 0
            when {
                response.status != HttpStatusCode.OK -> {
                    FetchResult.Failed
                }

                declared > MediaScanner.MAX_BYTES -> {
                    FetchResult.TooLarge
                }

                else -> {
                    val body = response.bodyAsChannel().readRemaining(MediaScanner.MAX_BYTES + 1L).readByteArray()
                    if (body.size > MediaScanner.MAX_BYTES) FetchResult.TooLarge else FetchResult.Bytes(body)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (ignored: Throwable) {
            FetchResult.Failed
        }

    override fun close() = client.close()

    private companion object {
        const val ACCEPT = "image/webp,image/gif"
        const val CONNECT_TIMEOUT_MS = 10_000L
        const val SOCKET_TIMEOUT_MS = 15_000L
        const val REQUEST_TIMEOUT_MS = 30_000L
    }
}
