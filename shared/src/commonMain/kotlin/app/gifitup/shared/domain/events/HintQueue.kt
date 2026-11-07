package app.gifitup.shared.domain.events

import app.gifitup.shared.domain.contacts.KeyBytes
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Where the server's hints end up (ADR-0015, #27 plan review SEC-6): hints are merged, and a [Wake] is handed out at most
 * once per [gapMillis] for **all** groups together. So a hostile or buggy server that names every group, or sends a hint per
 * microsecond, makes this device ask once a second, not once per hint. A hint for a group this device does not know causes
 * nothing at all, and the set of waiting groups is bounded: past [maxGroups] it becomes "ask about everything".
 */
internal class HintQueue(
    private val nowMillis: () -> Long,
    private val gapMillis: Long = DEFAULT_GAP_MILLIS,
    private val everythingGapMillis: Long = DEFAULT_EVERYTHING_GAP_MILLIS,
    private val maxGroups: Int = DEFAULT_MAX_GROUPS,
) {
    private val mutex = Mutex()
    private val groups = LinkedHashSet<KeyBytes>()
    private var welcomes = false
    private var everything = false
    private var lastWake = NEVER
    private var lastEverything = NEVER
    private val signal = Channel<Unit>(Channel.CONFLATED)

    suspend fun offer(
        message: ServerMessage,
        isKnown: suspend (KeyBytes) -> Boolean,
    ) {
        val kept =
            when (message) {
                is ServerMessage.GroupHint -> isKnown(message.groupId)
                else -> true
            }
        if (!kept) return
        mutex.withLock {
            when (message) {
                is ServerMessage.GroupHint -> if (groups.size >= maxGroups) everything = true else groups += message.groupId
                ServerMessage.WelcomeHint -> welcomes = true
                ServerMessage.Resync -> everything = true
            }
        }
        signal.trySend(Unit)
    }

    private sealed interface Outcome {
        data object Nothing : Outcome

        data class Wait(
            val millis: Long,
        ) : Outcome

        data class Take(
            val wake: Wake,
        ) : Outcome
    }

    /**
     * Waits for something to ask about, then for the gap since the last wake (a longer one if the wake asks about everything,
     * which costs a request per group), and hands everything waiting out as one wake. The wait is worked out again under the
     * lock after every pause: a `resync` that arrives while a group hint waits makes that wake an "everything" wake, which
     * then waits for its own, longer gap (#271 SEC-17).
     */
    suspend fun next(): Wake {
        while (true) {
            signal.receive()
            while (true) {
                when (val outcome = mutex.withLock { decide() }) {
                    Outcome.Nothing -> break
                    is Outcome.Wait -> delay(outcome.millis)
                    is Outcome.Take -> return outcome.wake
                }
            }
        }
    }

    /** What to do now, from what is waiting; called with the lock held. */
    private fun decide(): Outcome {
        if (groups.isEmpty() && !welcomes && !everything) return Outcome.Nothing
        val now = nowMillis()
        val wait = maxOf(gapMillis - (now - lastWake), if (everything) everythingGapMillis - (now - lastEverything) else 0L)
        return if (wait > 0) Outcome.Wait(wait) else Outcome.Take(take(now))
    }

    private fun take(now: Long): Wake {
        val wake = Wake(groups.toSet(), welcomes, everything)
        if (everything) lastEverything = now
        groups.clear()
        welcomes = false
        everything = false
        lastWake = now
        return wake
    }

    private companion object {
        const val DEFAULT_GAP_MILLIS = 1_000L
        const val DEFAULT_EVERYTHING_GAP_MILLIS = 5_000L
        const val DEFAULT_MAX_GROUPS = 64
        const val NEVER = Long.MIN_VALUE / 2
    }
}
