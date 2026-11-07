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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
 * through their own checked requests.
 *
 * Rules (plan review SEC-1, SEC-2, SEC-6, SEC-7, SEC-9):
 * - **One connection attempt at a time**; a second [start] does nothing. Every hello spends the account's request budget
 *   (ADR-0009), so the pace is bounded: back-off from 1 s up to 60 s with jitter, reset only after a connection that lived
 *   [CloseRules.STABLE_MILLIS].
 * - **A rule per close code** ([stepFor]): 4001 stops ("update the app"), 4400 stops, 4002 waits 5 minutes, 1008 (another
 *   socket of this device took the slot) waits 60 s, 1000 and 1001 reconnect after 5 s, everything else backs off.
 * - **4401 (the session was refused):** the rejected session is dropped ([SessionHolder.invalidate], never `clear`) and one
 *   fresh login is tried; a second 4401 in a row stops with [StopReason.SESSION_REFUSED].
 * - **The token** is in the hello and nowhere else: this class logs nothing, keeps no text of a failure and shows no frame.
 * - **A wipe or logout** (`SessionHolder.clear`): the next session request answers "not registered" and the loop stops; [stop]
 *   closes the socket first.
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

    private var job: Job? = null
    private var link: SocketLink? = null
    private var forced = false

    /** The next merged wake-up: waits for a hint about a known group, a Welcome or a resync, at most one per second. */
    suspend fun nextWake(): Wake = queue.next()

    /** Starts the loop if it is not running. */
    fun start() {
        if (job?.isActive == true) return
        job = scope.launch { run() }
    }

    /** Ends the loop and closes the socket. */
    suspend fun stop() {
        job?.cancel()
        job = null
        withContext(NonCancellable) { closeLink() }
        flow.value = LiveStatus(LiveState.STOPPED)
    }

    /**
     * Drops the socket and connects again after a second, without counting it as a failure: for the screens that notice a
     * message no hint announced (the socket is quiet but not working).
     */
    suspend fun reconnect() {
        forced = true
        closeLink()
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
        val memory = Memory()
        try {
            while (cycle(memory)) {
                // The next pass starts from the state the last one left.
            }
        } finally {
            withContext(NonCancellable) { closeLink() }
        }
    }

    /** One pass: a session, a socket, listening, then the wait its close code asks for. `false` ends the loop for good. */
    private suspend fun cycle(memory: Memory): Boolean {
        flow.value = LiveStatus(LiveState.CONNECTING)
        return when (val s = sessions.session()) {
            is AuthResult.Failed -> sessionFailed(memory, s.error)
            is AuthResult.Ok -> connect(memory, s.value)
        }
    }

    private suspend fun sessionFailed(
        memory: Memory,
        error: AuthError,
    ): Boolean {
        if (error == AuthError.Local.NOT_REGISTERED) {
            stopFor(StopReason.NOT_SIGNED_IN)
            return false
        }
        return failed(memory)
    }

    private suspend fun failed(memory: Memory): Boolean {
        memory.failures++
        wait(CloseRules.backoff(memory.failures, random))
        return true
    }

    private suspend fun connect(
        memory: Memory,
        session: Session,
    ): Boolean {
        val opened = open(session.endpoint.socketUrl, SocketFrames.hello(session)) ?: return failed(memory)
        link = opened
        val startedAt = nowMillis()
        flow.value = LiveStatus(LiveState.CONNECTED)
        val code = listen(opened)
        closeLink()
        if (nowMillis() - startedAt >= CloseRules.STABLE_MILLIS) {
            memory.failures = 0
            memory.relogged = false
        }
        return follow(memory, session, code)
    }

    /** The step after a socket ended: what its close code asks for, or a short wait if the screens dropped it themselves. */
    private suspend fun follow(
        memory: Memory,
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
                is SocketFrame.Text -> SocketFrames.parse(frame.text)?.let { queue.offer(it, isKnownGroup) }
                is SocketFrame.Closed -> return frame.code
            }
        }
    }

    private suspend fun wait(millis: Long) {
        flow.value = LiveStatus(LiveState.WAITING)
        delay(millis)
    }

    private fun stopFor(reason: StopReason) {
        flow.value = LiveStatus(LiveState.STOPPED, reason)
    }
}
