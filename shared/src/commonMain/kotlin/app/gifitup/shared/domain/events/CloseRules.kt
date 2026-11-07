package app.gifitup.shared.domain.events

import app.gifitup.shared.data.socket.CloseCodes
import kotlin.math.min

/**
 * The pace and the rules of the push channel's reconnects (#27 plan review SEC-1, SEC-2): what to do after the socket ended
 * with a given close code, and how long to wait after a failure. Pure, so the table is tested on its own.
 */
internal object CloseRules {
    /** A connection that lived this long counts as a good one: the back-off and the one-re-login rule start again. */
    const val STABLE_MILLIS = 30_000L

    /** Reconnect after the screens asked for it ([LiveUpdates.reconnect]): not a failure, so no back-off. */
    const val FORCED_MILLIS = 1_000L

    private const val INITIAL_BACKOFF_MILLIS = 1_000L
    private const val MAX_BACKOFF_MILLIS = 60_000L
    private const val MAX_SHIFT = 6
    private const val JITTER = 0.25
    private const val OUTDATED_SERVER_MILLIS = 5 * 60_000L
    private const val EVICTED_MILLIS = 60_000L
    private const val CLEAN_CLOSE_MILLIS = 5_000L

    sealed interface Step {
        data class Stop(
            val reason: StopReason,
        ) : Step

        data object Relogin : Step

        data class Wait(
            val millis: Long,
        ) : Step

        data object Backoff : Step
    }

    /** What to do after the socket ended with [code] (`null`: it dropped without one); [relogged]: a fresh login was just tried. */
    fun stepFor(
        code: Int?,
        relogged: Boolean,
    ): Step =
        when (code) {
            CloseCodes.CLIENT_OUTDATED -> Step.Stop(StopReason.OUTDATED_CLIENT)
            CloseCodes.BAD_HANDSHAKE -> Step.Stop(StopReason.PROTOCOL_ERROR)
            CloseCodes.SERVER_OUTDATED -> Step.Wait(OUTDATED_SERVER_MILLIS)
            CloseCodes.UNAUTHORIZED -> if (relogged) Step.Stop(StopReason.SESSION_REFUSED) else Step.Relogin
            CloseCodes.POLICY_VIOLATION -> Step.Wait(EVICTED_MILLIS)
            CloseCodes.NORMAL, CloseCodes.GOING_AWAY -> Step.Wait(CLEAN_CLOSE_MILLIS)
            else -> Step.Backoff
        }

    /** 1 s, 2 s, 4 s, … up to 60 s after the n-th failure in a row, with up to 25% jitter from [random] (0 up to 1). */
    fun backoff(
        failures: Int,
        random: () -> Double,
    ): Long {
        val base = min(MAX_BACKOFF_MILLIS, INITIAL_BACKOFF_MILLIS shl min(failures - 1, MAX_SHIFT))
        return (base * (1 + JITTER * random())).toLong()
    }
}
