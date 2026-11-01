package app.gifitup.shared.domain.gif

import app.gifitup.shared.data.network.GifProviderKey
import app.gifitup.shared.domain.auth.AuthResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * Fetches the key from our server (#40) and keeps it fresh (ADR-0003, #23): once when [run] starts (with the login), then
 * every [refreshMillis] while it runs, and after a failed fetch again after [retryMillis] (growing, up to
 * [maxRetryMillis]). **Only on this schedule**: the picker, a provider 401/403 or a 429 never cause a fetch, so the server
 * can't tell when someone is about to send a GIF. The caller runs it in the logged-in session's scope and cancels it, and
 * clears the holder, at logout.
 */
class GifKeyRefresher(
    private val holder: GifKeyHolder,
    private val fetch: suspend () -> AuthResult<GifProviderKey>,
    private val refreshMillis: Long = SIX_HOURS_MILLIS,
    private val retryMillis: Long = FIVE_MINUTES_MILLIS,
    private val maxRetryMillis: Long = ONE_HOUR_MILLIS,
) {
    @Suppress("TooGenericExceptionCaught") // Whatever the fetch throws is a failed fetch; nothing of it is kept.
    suspend fun run() {
        var wait = retryMillis
        while (true) {
            val key =
                try {
                    (fetch() as? AuthResult.Ok)?.value
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    null
                }
            if (key != null) {
                holder.set(key)
                wait = retryMillis
                delay(refreshMillis)
            } else {
                delay(wait)
                wait = minOf(wait * 2, maxRetryMillis)
            }
        }
    }

    companion object {
        const val SIX_HOURS_MILLIS = 6L * 60 * 60 * 1000
        const val FIVE_MINUTES_MILLIS = 5L * 60 * 1000
        const val ONE_HOUR_MILLIS = 60L * 60 * 1000
    }
}
