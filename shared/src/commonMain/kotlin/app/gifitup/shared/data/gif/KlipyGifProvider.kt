package app.gifitup.shared.data.gif

import app.gifitup.shared.data.network.GifProviderKey
import app.gifitup.shared.domain.gif.GifFailure
import app.gifitup.shared.domain.gif.GifKeyHolder
import app.gifitup.shared.domain.gif.GifProvider
import app.gifitup.shared.domain.gif.GifResult
import io.ktor.http.URLBuilder
import io.ktor.http.URLProtocol
import io.ktor.http.appendPathSegments
import kotlinx.coroutines.CancellationException

/**
 * [GifProvider] for Klipy (ADR-0003, `docs/spikes/gif-provider.md`): the one place that knows Klipy's host, path and answer.
 *
 * - **Request:** built only with `URLBuilder` (never by joining strings), to the fixed host `api.klipy.com`: the key (a
 *   [GifProviderKey], so one `[A-Za-z0-9_-]` segment) is a path segment, the query and the page are parameters, and
 *   `content_filter` is added last on every request so no value of ours overrides it and a key from another account
 *   can't switch the filter off. No `customer_id`.
 * - **Answer:** whole or placeholder, see [KlipyParser]. The body is capped while it streams ([MAX_BODY_BYTES]).
 * - **Failures are closed** ([GifFailure]); any throwable is caught at this edge and its cause dropped, because the key is in
 *   the URL and a platform exception's message can quote the URL. Nothing here logs.
 * - **Limits:** the key is shared by every friend. A call within [MIN_INTERVAL_MILLIS] of the last one, or before a `429`'s
 *   `Retry-After` is over, is refused without a request. There is no automatic retry. The picker also cancels a search when a
 *   new one starts.
 * - **Logout:** a call that started before [GifKeyHolder.clear] brings nothing back.
 */
class KlipyGifProvider(
    private val fetcher: ProviderFetcher,
    private val keys: GifKeyHolder,
    private val nowMillis: () -> Long,
) : GifProvider {
    private var lastCallAt = Long.MIN_VALUE / 2
    private var blockedUntil = Long.MIN_VALUE / 2

    override suspend fun trending(page: Int): GifResult = call(TRENDING, null, page)

    override suspend fun search(
        query: String,
        page: Int,
    ): GifResult {
        val text = query.trim().take(MAX_QUERY_CHARS)
        return if (text.isEmpty()) call(TRENDING, null, page) else call(SEARCH, text, page)
    }

    @Suppress("TooGenericExceptionCaught", "ReturnCount") // The cause is dropped on purpose; one early exit per guard.
    private suspend fun call(
        endpoint: String,
        query: String?,
        page: Int,
    ): GifResult {
        val key = keys.current() ?: return GifResult.Failed(GifFailure.UNAVAILABLE)
        if (page !in 1..MAX_PAGE) return GifResult.Failed(GifFailure.MALFORMED)
        val now = nowMillis()
        if (now < blockedUntil) return GifResult.Failed(GifFailure.RATE_LIMITED, secondsUntil(blockedUntil - now))
        if (now - lastCallAt < MIN_INTERVAL_MILLIS) return GifResult.Failed(GifFailure.RATE_LIMITED, 1)
        lastCallAt = now
        val epoch = keys.epoch()
        return try {
            val answer = fetcher.get(urlOf(key, endpoint, query, page), MAX_BODY_BYTES)
            if (keys.epoch() != epoch) GifResult.Failed(GifFailure.UNAVAILABLE) else resultOf(answer)
        } catch (e: CancellationException) {
            throw e
        } catch (ignored: Throwable) {
            GifResult.Failed(GifFailure.MALFORMED)
        }
    }

    private fun resultOf(answer: ProviderAnswer): GifResult =
        when (answer) {
            ProviderAnswer.Failed -> {
                GifResult.Failed(GifFailure.OFFLINE)
            }

            ProviderAnswer.TooLarge -> {
                GifResult.Failed(GifFailure.MALFORMED)
            }

            is ProviderAnswer.Reply -> {
                when (answer.status) {
                    OK -> KlipyParser.parse(answer.body)
                    TOO_MANY_REQUESTS -> rateLimited(answer.retryAfterSeconds)
                    else -> GifResult.Failed(GifFailure.UNAVAILABLE)
                }
            }
        }

    private fun rateLimited(retryAfter: Int?): GifResult {
        val seconds = (retryAfter ?: DEFAULT_RETRY_AFTER_SECONDS).coerceIn(1, MAX_RETRY_AFTER_SECONDS)
        blockedUntil = nowMillis() + seconds * MILLIS
        return GifResult.Failed(GifFailure.RATE_LIMITED, seconds)
    }

    private fun secondsUntil(millis: Long): Int = ((millis + MILLIS - 1) / MILLIS).toInt().coerceAtLeast(1)

    internal fun urlOf(
        key: GifProviderKey,
        endpoint: String,
        query: String?,
        page: Int,
    ): String =
        URLBuilder()
            .apply {
                protocol = URLProtocol.HTTPS
                host = HOST
                appendPathSegments("api", "v1", key.reveal(), "gifs", endpoint)
                query?.let { parameters.append("q", it) }
                parameters.append("page", page.toString())
                parameters.append("per_page", PER_PAGE.toString())
                parameters.append("format_filter", "gif,webp")
                // Last, on every request. UNVERIFIED value (the docs are not reachable from the sandbox): the first live check
                // with the dev CLI (`gif-search`) confirms it; a wrong value shows as UNAVAILABLE there.
                parameters.append("content_filter", CONTENT_FILTER)
            }.buildString()

    companion object {
        const val HOST = "api.klipy.com"
        const val TRENDING = "trending"
        const val SEARCH = "search"
        const val CONTENT_FILTER = "medium"
        const val PER_PAGE = 24
        const val MAX_PAGE = 20
        const val MAX_QUERY_CHARS = 100
        const val MAX_BODY_BYTES = 512 * 1024
        const val MIN_INTERVAL_MILLIS = 300L
        private const val OK = 200
        private const val TOO_MANY_REQUESTS = 429
        private const val DEFAULT_RETRY_AFTER_SECONDS = 60
        private const val MAX_RETRY_AFTER_SECONDS = 300
        private const val MILLIS = 1000L
    }
}
