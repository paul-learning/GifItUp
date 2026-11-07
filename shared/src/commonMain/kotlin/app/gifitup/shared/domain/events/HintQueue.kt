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
    private val maxGroups: Int = DEFAULT_MAX_GROUPS,
) {
    private val mutex = Mutex()
    private val groups = LinkedHashSet<KeyBytes>()
    private var welcomes = false
    private var everything = false
    private var lastWake = NEVER
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

    /** Waits for something to ask about, then for the gap since the last wake, and hands everything waiting out as one wake. */
    suspend fun next(): Wake {
        while (true) {
            signal.receive()
            val wait = gapMillis - (nowMillis() - lastWake)
            if (wait > 0) delay(wait)
            val wake =
                mutex.withLock {
                    if (groups.isEmpty() && !welcomes && !everything) {
                        null
                    } else {
                        Wake(groups.toSet(), welcomes, everything).also {
                            groups.clear()
                            welcomes = false
                            everything = false
                            lastWake = nowMillis()
                        }
                    }
                }
            if (wake != null) return wake
        }
    }

    private companion object {
        const val DEFAULT_GAP_MILLIS = 1_000L
        const val DEFAULT_MAX_GROUPS = 64
        const val NEVER = Long.MIN_VALUE / 2
    }
}
