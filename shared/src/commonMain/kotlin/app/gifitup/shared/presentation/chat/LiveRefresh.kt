package app.gifitup.shared.presentation.chat

import app.gifitup.shared.domain.contacts.KeyBytes
import app.gifitup.shared.domain.events.Wake
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * What a hint makes the screens do (#27, ADR-0015): the one consumer of [LiveUpdates][app.gifitup.shared.domain.events.LiveUpdates]'
 * wakes. A hint decides nothing; it only makes this device ask, through the same single-flight reads the timers use.
 *
 * - **One at a time.** The work is a queue drained by one loop, never in parallel: a wake that arrives while a read runs is
 *   merged into what waits.
 * - **A global cap on requests.** At most one group read per [tickMillis] (default 1 s), whatever the wakes say, so a hostile
 *   stream of hints (every group, a `resync` every 5 s) costs about one request a second plus the open conversation's, well
 *   under the server's read limit (burst 120, one more per 500 ms). An "everything" wake queues every known group and the
 *   welcome check instead of reading them at once.
 * - **The open conversation first**, then the other groups in the order they were named.
 * - **The nextWake() wait is never cancelled** while it waits: a separate collector coroutine owns it and ends only with
 *   [stop].
 */
class LiveRefresh(
    private val scope: CoroutineScope,
    private val nextWake: suspend () -> Wake,
    private val target: Target,
    private val tickMillis: Long = TICK_MILLIS,
) {
    /** The screens' side: what exists to refresh, and how. */
    interface Target {
        /** The groups this device knows: a snapshot, never one listing per hint. */
        suspend fun knownGroups(): List<KeyBytes>

        /** The group of the open conversation, if one is open. */
        fun openGroup(): KeyBytes?

        /** Reads [group]'s log through the open conversation's own single-flight read. */
        suspend fun refreshOpen()

        /** Reads one other group's log and rebuilds the list from the stored history. */
        suspend fun refreshGroup(group: KeyBytes)

        /** Joins the groups this device was added to, and rebuilds the list. */
        suspend fun refreshWelcomes()
    }

    private val lock = Mutex()
    private val waiting = LinkedHashSet<KeyBytes>()
    private var welcomes = false
    private val signal = Channel<Unit>(Channel.CONFLATED)
    private var collector: Job? = null
    private var worker: Job? = null

    fun start() {
        if (collector?.isActive == true) return
        collector =
            scope.launch {
                while (isActive) absorb(nextWake())
            }
        worker =
            scope.launch {
                while (isActive) {
                    signal.receive()
                    drain()
                }
            }
    }

    suspend fun stop() {
        collector?.cancel()
        worker?.cancel()
        collector = null
        worker = null
        lock.withLock {
            waiting.clear()
            welcomes = false
        }
    }

    private suspend fun absorb(wake: Wake) {
        val everything = if (wake.everything) target.knownGroups() else emptyList()
        lock.withLock {
            waiting += wake.groups
            waiting += everything
            welcomes = welcomes || wake.welcomes || wake.everything
        }
        signal.trySend(Unit)
    }

    /** Works through what waits, one read per tick, the welcome check first (it can add a group the hints name). */
    private suspend fun drain() {
        while (true) {
            val step = next() ?: return
            // One failed read must not end the worker for the rest of the session; a cancellation still ends it.
            try {
                step()
            } catch (e: CancellationException) {
                throw e
            } catch (
                @Suppress("TooGenericExceptionCaught") _: Exception,
            ) {
                // The read's own result already reports its problem to the screen; a hint decides nothing.
            }
            delay(tickMillis)
        }
    }

    private suspend fun next(): (suspend () -> Unit)? {
        val open = target.openGroup()
        return lock.withLock {
            when {
                welcomes -> {
                    welcomes = false
                    suspend { target.refreshWelcomes() }
                }

                open != null && waiting.remove(open) -> {
                    suspend { target.refreshOpen() }
                }

                waiting.isNotEmpty() -> {
                    val group = waiting.first()
                    waiting.remove(group)
                    suspend { target.refreshGroup(group) }
                }

                else -> {
                    null
                }
            }
        }
    }

    companion object {
        const val TICK_MILLIS = 1_000L
    }
}
