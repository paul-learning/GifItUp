package app.gifitup.shared.data.gif

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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.io.readByteArray

/**
 * [ProviderFetcher] over Ktor, for Android and the JVM. **Not for the web** (a browser follows redirects by itself and
 * decides about credentials and the referrer; the web has `BrowserProviderFetcher`). No cookie storage and no logging plugin
 * are installed, no `Referer` or app-specific header is set (the engine's own `User-Agent` is sent, which the provider sees
 * anyway: ADR-0003), redirects are not followed, and nothing but the status, the `Retry-After` seconds and a capped `200`
 * body leaves this class.
 */
class KtorProviderFetcher(
    engine: HttpClientEngine,
) : ProviderFetcher,
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

    /**
     * Never cancelled while the request is on the wire, for the reason given at [KtorMediaFetcher.fetch]: a newer search
     * cancels the older one, and cancelling an Android `HttpURLConnection` response mid-read can crash the app. The older answer
     * is dropped by the caller.
     */
    override suspend fun get(
        url: String,
        maxBytes: Int,
    ): ProviderAnswer {
        val answer = withContext(NonCancellable) { getToEnd(url, maxBytes) }
        // withContext(NonCancellable) returns normally for a caller that was cancelled meanwhile: say so now.
        currentCoroutineContext().ensureActive()
        return answer
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun getToEnd(
        url: String,
        maxBytes: Int,
    ): ProviderAnswer =
        try {
            client.prepareGet(url) { header(HttpHeaders.Accept, ACCEPT) }.execute { response -> answerOf(response, maxBytes) }
        } catch (e: CancellationException) {
            throw e
        } catch (ignored: Throwable) {
            // Engines fail with platform-specific types (the JS fetch engine throws kotlin.Error), and their messages can
            // quote the URL, which holds the key: the cause is dropped here, never forwarded.
            ProviderAnswer.Failed
        }

    @Suppress("ReturnCount") // One early exit per refusal.
    private suspend fun answerOf(
        response: HttpResponse,
        maxBytes: Int,
    ): ProviderAnswer {
        val status = response.status.value
        val retryAfter = retryAfterSeconds(response.headers[HttpHeaders.RetryAfter])
        if (response.status != HttpStatusCode.OK) {
            response.bodyAsChannel().cancel(null)
            return ProviderAnswer.Reply(status, ByteArray(0), retryAfter)
        }
        if ((response.contentLength() ?: 0) > maxBytes) return ProviderAnswer.TooLarge
        val body = response.bodyAsChannel().readRemaining(maxBytes + 1L).readByteArray()
        return if (body.size > maxBytes) ProviderAnswer.TooLarge else ProviderAnswer.Reply(status, body, retryAfter)
    }

    override fun close() = client.close()

    private companion object {
        const val ACCEPT = "application/json"
        const val CONNECT_TIMEOUT_MS = 10_000L
        const val SOCKET_TIMEOUT_MS = 15_000L
        const val REQUEST_TIMEOUT_MS = 30_000L
    }
}

/** Whole seconds, 0..3600, from a `Retry-After` header; an HTTP date or anything else is `null`. */
internal fun retryAfterSeconds(header: String?): Int? =
    header
        ?.takeIf { it.length in 1..MAX_RETRY_AFTER_DIGITS && it.all(Char::isDigit) }
        ?.toInt()
        ?.takeIf { it <= MAX_RETRY_AFTER_SECONDS }

private const val MAX_RETRY_AFTER_SECONDS = 3600
private const val MAX_RETRY_AFTER_DIGITS = 4
