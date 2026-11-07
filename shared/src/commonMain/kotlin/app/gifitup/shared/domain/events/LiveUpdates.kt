package app.gifitup.shared.domain.events

import app.gifitup.shared.data.network.Session
import app.gifitup.shared.data.socket.SocketFrames
import app.gifitup.shared.domain.auth.AuthError
import app.gifitup.shared.domain.auth.AuthResult
import app.gifitup.shared.domain.contacts.KeyBytes
import app.gifitup.shared.domain.session.SessionHolder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.random.Random

enum class LiveState { STOPPED, CONNECTING, CONNECTED, WAITING }

/** Why the loop ended for good (it never ends for a network problem). */
enum class StopReason {
    /** The server refused this client's protocol version (close code 4001): the app must be updated. */
    OUTDATED_CLIENT,

    /** The server rejected the hello as malformed (4400): a defect of this client, retrying cannot help. */
    PROTOCOL_ERROR,

    /** The server refused a fresh session too (a second 4401): retrying would only spend the login budget. */
    SESSION_REFUSED,

    /** There is no registered identity on this device (a wipe, a logout). */
    NOT_SIGNED_IN,
}

class LiveStatus(
    val state: LiveState,
    val stopped: StopReason? = null,
) {
    val connected: Boolean get() = state == LiveState.CONNECTED

    override fun toString(): String = "LiveStatus($state${stopped?.let { ", $it" } ?: ""})"
}

/**
 * The client side of the server's push channel (ADR-0015, #27): keeps one WebSocket open while the app is in use and turns
 * the hints it gets into [Wake]s through [nextWake]. It never carries content, and a hint decides nothing: the screens ask
 * through their own checked requests. Used from one dispatcher (the screens' own); [start], [stop] and [reconnect] are
 * serialised.
 *
 * Rules (plan review SEC-1, SEC-2, SEC-6, SEC-7, SEC-9, and the PR 1a review SEC-11 to SEC-15):
 * - **One connection attempt at a time**; a second [start] does nothing. Every hello spends the account's request budget
 *   (ADR-0009), so the pace is bounded: back-off from 1 s up to 60 s with jitter, never two attempts less than a second apart,
 *   and the counters live as long as this object, not as long as one [start]: a foreground that comes and goes does not reset
 *   them. They start again only after a connection that lived [CloseRules.STABLE_MILLIS] or after a long quiet time.
 * - **A rule per close code** ([CloseRules.stepFor]): 4001 stops ("update the app"), 4400 stops, 4002 waits 5 minutes, 1008
 *   (another socket of this device took the slot) waits 60 s, 1000 and 1001 reconnect after 5 s, everything else backs off.
 * - **A stop is remembered:** after 4001 or 4400 [start] does nothing until the process ends; after a repeated 4401 it does
 *   nothing for ten minutes. A refused session costs one login and two hellos per refusal chain, not per foreground.
 * - **4401 (the session was refused):** the rejected session is dropped ([SessionHolder.invalidate], never `clear`) and one
 *   fresh login is tried; a second 4401 in a row stops with [StopReason.SESSION_REFUSED].
 * - **The token** is in the hello and nowhere else: this class logs nothing, keeps no text of a failure and shows no frame.
 * - **A wipe or logout** (`SessionHolder.clear`) closes an open socket at once (the socket is tied to the session generation)
 *   and the next session request answers "not registered", so the loop stops. A clear in the instant between the check and the
 *   hello cannot be undone: that hello is already on the wire, and the socket closes right after.
 */
@Suppress("TooManyFunctions") // Five public functions and the small steps of the one loop (connect, listen, follow).
class LiveUpdates(
    private val connector: SocketConnector,
    private val sessions: SessionHolder,
    private val scope: CoroutineScope,
    private val isKnownGroup: suspend (KeyBytes) -> Boolean,
    private val nowMillis: () -> Long,
    private val random: () -> Double = { Random.nextDouble() },
) {
    private val queue = HintQueue(nowMillis)
    private val flow = MutableStateFlow(LiveStatus(LiveState.STOPPED))
    val status: StateFlow<LiveStatus> = flow.asStateFlow()

    private val lifecycle = Mutex()
    private val memory = Memory()
    private var job: Job? = null
    private var link: SocketLink? = null
    private var forced = false
    private var blockedUntil = 0L
    private var lastOpenAt = NEVER
    private var lastForcedAt = NEVER

    /** The next merged wake-up: waits for a hint about a known group, a Welcome or a resync, at most one per second. */
    suspend fun nextWake(): Wake = queue.next()

    /** Starts the loop if it is not running and the last stop allows it. */
    suspend fun start() {
        lifecycle.withLock {
            if (job?.isActive == true || nowMillis() < blockedUntil) return
            if (nowMillis() - lastOpenAt > QUIET_RESET_MILLIS) memory.failures = 0
            if (flow.value.stopped == StopReason.SESSION_REFUSED) memory.relogged = false
            job = scope.launch { run() }
        }
    }

    /** Ends the loop, waits for it to end, and closes the socket. */
    suspend fun stop() {
        lifecycle.withLock {
            job?.cancelAndJoin()
            job = null
            withContext(NonCancellable) { closeLink() }
            flow.value = LiveStatus(LiveState.STOPPED, flow.value.stopped)
        }
    }

    /**
     * Drops an open socket and connects again after a second, without counting it as a failure: for the screens that notice a
     * message no hint announced (the socket is quiet but not working). Does nothing if no socket is open, and at most once per
     * [CloseRules.STABLE_MILLIS], so the screens' timers cannot make a loop of it.
     */
    suspend fun reconnect() {
        lifecycle.withLock {
            val now = nowMillis()
            if (link == null || now - lastForcedAt < CloseRules.STABLE_MILLIS) return
            lastForcedAt = now
            forced = true
            closeLink()
        }
    }

    private suspend fun closeLink() {
        val open = link
        link = null
        runCatching { open?.close() }
    }

    /** What the loop remembers between passes. */
    private class Memory {
        var failures = 0
        var relogged = false
    }

    private suspend fun run() {
        try {
            while (cycle()) {
                // The next pass starts from the state the last one left.
            }
        } finally {
            withContext(NonCancellable) { closeLink() }
        }
    }

    /** One pass: a session, a socket, listening, then the wait its close code asks for. `false` ends the loop for good. */
    private suspend fun cycle(): Boolean {
        flow.value = LiveStatus(LiveState.CONNECTING)
        return when (val s = sessions.session()) {
            is AuthResult.Failed -> sessionFailed(s.error)
            is AuthResult.Ok -> connect(s.value)
        }
    }

    private suspend fun sessionFailed(error: AuthError): Boolean {
        if (error == AuthError.Local.NOT_REGISTERED) {
            stopFor(StopReason.NOT_SIGNED_IN)
            return false
        }
        return failed()
    }

    private suspend fun failed(): Boolean {
        memory.failures++
        wait(CloseRules.backoff(memory.failures, random))
        return true
    }

    private suspend fun connect(session: Session): Boolean {
        val generation = sessions.mark()
        // Never two attempts less than a second apart, however the screens start and stop us.
        val sinceLast = nowMillis() - lastOpenAt
        if (sinceLast < MIN_OPEN_GAP_MILLIS) delay(MIN_OPEN_GAP_MILLIS - sinceLast)
        // A wipe, a logout or an origin change since the session was handed out: this session is not to be used.
        return if (sessions.mark() != generation) failed() else openAndListen(session, generation)
    }

    private suspend fun openAndListen(
        session: Session,
        generation: Int,
    ): Boolean {
        lastOpenAt = nowMillis()
        val opened = open(session.endpoint.socketUrl, SocketFrames.hello(session)) ?: return failed()
        link = opened
        val startedAt = nowMillis()
        flow.value = LiveStatus(LiveState.CONNECTED)
        val code = listenUntilCleared(opened, generation)
        closeLink()
        if (nowMillis() - startedAt >= CloseRules.STABLE_MILLIS) {
            memory.failures = 0
            memory.relogged = false
        }
        return follow(session, code)
    }

    /** Listens; a wipe or logout while the socket is open closes it at once (the socket is tied to the session generation). */
    private suspend fun listenUntilCleared(
        open: SocketLink,
        generation: Int,
    ): Int? =
        coroutineScope {
            val watcher =
                launch {
                    sessions.generations.first { it != generation }
                    closeLink()
                }
            try {
                listen(open)
            } finally {
                watcher.cancel()
            }
        }

    /** The step after a socket ended: what its close code asks for, or a short wait if the screens dropped it themselves. */
    private suspend fun follow(
        session: Session,
        code: Int?,
    ): Boolean {
        val dropped = forced.also { forced = false }
        if (!dropped) memory.failures++
        return when (
            val step =
                if (dropped) {
                    CloseRules.Step.Wait(
                        CloseRules.FORCED_MILLIS,
                    )
                } else {
                    CloseRules.stepFor(code, memory.relogged)
                }
        ) {
            is CloseRules.Step.Stop -> {
                stopFor(step.reason)
                false
            }

            CloseRules.Step.Relogin -> {
                sessions.invalidate(session)
                memory.relogged = true
                true
            }

            is CloseRules.Step.Wait -> {
                wait(step.millis)
                true
            }

            CloseRules.Step.Backoff -> {
                wait(CloseRules.backoff(memory.failures, random))
                true
            }
        }
    }

    @Suppress("TooGenericExceptionCaught") // Whatever the library throws, the answer is "could not connect"; nothing of it is kept.
    private suspend fun open(
        url: String,
        hello: String,
    ): SocketLink? =
        try {
            (connector.open(url, hello) as? SocketOpening.Opened)?.link
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }

    /** Reads until the socket ends; the close code, or `null` for a drop. Anything but a valid hint is ignored. */
    @Suppress("TooGenericExceptionCaught") // A failing read is the end of the socket; nothing of the failure is kept.
    private suspend fun listen(open: SocketLink): Int? {
        while (true) {
            val frame =
                try {
                    open.receive()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    return null
                }
            when (frame) {
                is SocketFrame.Text -> SocketFrames.parse(frame.text)?.let { offer(it) }
                is SocketFrame.Closed -> return frame.code
            }
        }
    }

    /** A hint whose group lookup fails is dropped: one bad lookup must not end the loop. */
    @Suppress("TooGenericExceptionCaught") // Whatever the lookup throws, the hint is dropped and nothing of it is kept.
    private suspend fun offer(message: ServerMessage) {
        try {
            queue.offer(message, isKnownGroup)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Dropped.
        }
    }

    private suspend fun wait(millis: Long) {
        flow.value = LiveStatus(LiveState.WAITING)
        delay(millis)
    }

    private fun stopFor(reason: StopReason) {
        blockedUntil =
            when (reason) {
                StopReason.OUTDATED_CLIENT, StopReason.PROTOCOL_ERROR -> Long.MAX_VALUE
                StopReason.SESSION_REFUSED -> nowMillis() + REFUSED_BLOCK_MILLIS
                StopReason.NOT_SIGNED_IN -> 0L
            }
        flow.value = LiveStatus(LiveState.STOPPED, reason)
    }

    private companion object {
        const val NEVER = Long.MIN_VALUE / 2
        const val MIN_OPEN_GAP_MILLIS = 1_000L
        const val QUIET_RESET_MILLIS = 10 * 60_000L
        const val REFUSED_BLOCK_MILLIS = 10 * 60_000L
    }
}
