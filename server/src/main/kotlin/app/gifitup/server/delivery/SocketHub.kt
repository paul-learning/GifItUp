package app.gifitup.server.delivery

import io.netty.channel.Channel
import io.netty.channel.ChannelFutureListener
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Why the server closes a socket (the close codes of ADR-0006 A2 and ADR-0015). */
internal enum class SocketClose(
    val code: Int,
    val reason: String,
) {
    TIMEOUT(CLOSE_GOING_AWAY, "timeout"),
    TRY_LATER(CLOSE_TRY_AGAIN_LATER, "try later"),
    CLIENT_OUTDATED(app.gifitup.protocol.SocketCloseCodes.CLIENT_OUTDATED, "client outdated"),
    SERVER_OUTDATED(app.gifitup.protocol.SocketCloseCodes.SERVER_OUTDATED, "server outdated"),
    BAD_HANDSHAKE(app.gifitup.protocol.SocketCloseCodes.BAD_HANDSHAKE, "bad handshake"),
    UNAUTHORIZED(app.gifitup.protocol.SocketCloseCodes.UNAUTHORIZED, "unauthorized"),
    REPLACED(CLOSE_POLICY, "replaced"),
}

private const val CLOSE_GOING_AWAY = 1001
private const val CLOSE_POLICY = 1008
private const val CLOSE_TRY_AGAIN_LATER = 1013

/** Closes [this] with a close frame carrying [why], then the connection. Safe to call from any thread. */
internal fun Channel.closeWith(why: SocketClose) {
    if (isActive) writeAndFlush(CloseWebSocketFrame(why.code, why.reason)).addListener(ChannelFutureListener.CLOSE)
}

/** One authenticated socket: its channel, the session it belongs to, and whether a push was dropped for lack of room. */
internal class ClientSocket(
    val device: UUID,
    val sessionKey: String,
    val channel: Channel,
) {
    val resyncDue = AtomicBoolean(false)
}

/**
 * The online sockets (ADR-0015): which devices are connected, with the caps of the ADR. An unauthenticated socket
 * holds one of [maxUnauthenticated] places until its first frame authenticates it; an authenticated device holds at
 * most [maxPerDevice] sockets (a third closes the oldest), and [maxAuthenticated] exist in all. Pushes are hints that
 * carry no content, so dropping one is harmless; a socket that cannot take a write gets one resync hint instead.
 */
internal class SocketHub(
    private val maxUnauthenticated: Int = MAX_UNAUTHENTICATED,
    private val maxAuthenticated: Int = MAX_AUTHENTICATED,
    private val maxPerDevice: Int = MAX_PER_DEVICE,
) {
    private val byDevice = HashMap<UUID, MutableList<ClientSocket>>()
    private var authenticated = 0
    private var unauthenticated = 0

    /** Takes one of the unauthenticated places; false when they are all taken. */
    @Synchronized
    fun reserveUnauthenticated(): Boolean = (unauthenticated < maxUnauthenticated).also { if (it) unauthenticated++ }

    @Synchronized
    fun releaseUnauthenticated() {
        if (unauthenticated > 0) unauthenticated--
    }

    /**
     * Moves a socket from the unauthenticated places to the authenticated ones. False (and the unauthenticated place
     * is released) when [maxAuthenticated] sockets exist; otherwise the oldest socket of the device beyond
     * [maxPerDevice] is closed.
     */
    fun attach(socket: ClientSocket): Boolean {
        val evicted = ArrayList<ClientSocket>()
        val ok =
            synchronized(this) {
                if (unauthenticated > 0) unauthenticated--
                if (authenticated >= maxAuthenticated) {
                    false
                } else {
                    val list = byDevice.getOrPut(socket.device) { ArrayList() }
                    list.add(socket)
                    authenticated++
                    while (list.size > maxPerDevice) {
                        evicted.add(list.removeAt(0))
                        authenticated--
                    }
                    true
                }
            }
        evicted.forEach { it.channel.closeWith(SocketClose.REPLACED) }
        return ok
    }

    @Synchronized
    fun detach(socket: ClientSocket) {
        val list = byDevice[socket.device] ?: return
        if (list.remove(socket)) authenticated--
        if (list.isEmpty()) byDevice.remove(socket.device)
    }

    /** Sends [text] to every socket of [devices]; a socket that cannot take a write is marked for a resync hint. */
    fun push(
        devices: Collection<UUID>,
        text: String,
    ) {
        val targets = synchronized(this) { devices.flatMap { byDevice[it].orEmpty() } }
        targets.forEach { socket ->
            if (socket.channel.isWritable) {
                socket.channel.writeAndFlush(TextWebSocketFrame(text))
            } else {
                socket.resyncDue.set(true)
            }
        }
    }

    /** Closes every socket that belongs to the session ([sessionKey]), at once (logout). */
    fun closeSession(sessionKey: String) {
        val targets = synchronized(this) { byDevice.values.flatten().filter { it.sessionKey == sessionKey } }
        targets.forEach { it.channel.closeWith(SocketClose.UNAUTHORIZED) }
    }

    @Synchronized
    fun onlineSockets(): Int = authenticated

    companion object {
        const val MAX_UNAUTHENTICATED = 50
        const val MAX_AUTHENTICATED = 500
        const val MAX_PER_DEVICE = 2
    }
}
