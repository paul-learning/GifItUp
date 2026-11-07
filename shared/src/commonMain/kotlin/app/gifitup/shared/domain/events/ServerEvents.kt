package app.gifitup.shared.domain.events

import app.gifitup.shared.domain.contacts.KeyBytes

/**
 * What the server's socket said (ADR-0015): only that something is waiting, never what. None of it is trusted or stored: a
 * hint only makes this device ask, through its own checked requests (the "labels the server supplies decide nothing" rule).
 */
sealed interface ServerMessage {
    /** A message or commit arrived in the group [groupId] (the group id as this device knows it). */
    data class GroupHint(
        val groupId: KeyBytes,
    ) : ServerMessage

    /** A new Welcome is pending for this device. */
    data object WelcomeHint : ServerMessage

    /** The server had to drop hints for a slow socket: ask about everything. */
    data object Resync : ServerMessage
}

/**
 * One wake-up for the screens: ask about [groups] (known groups only), about the pending Welcomes, or about [everything].
 * Hints are merged and handed out at most once a second for all groups together (see [HintQueue]).
 */
class Wake(
    val groups: Set<KeyBytes>,
    val welcomes: Boolean,
    val everything: Boolean,
) {
    override fun toString(): String = "Wake(groups=${groups.size}, welcomes=$welcomes, everything=$everything)"
}

/** Opens the socket (ADR-0015). One implementation per HTTP engine; the loop knows nothing about the library. */
interface SocketConnector {
    /**
     * Connects to [url] and sends [hello] as the first text frame. [hello] holds the session token: it goes into that one
     * frame and nowhere else (not into a URL, a log or an exception text).
     */
    suspend fun open(
        url: String,
        hello: String,
    ): SocketOpening
}

sealed interface SocketOpening {
    class Opened(
        val link: SocketLink,
    ) : SocketOpening

    /** Could not connect (network, TLS, a refused upgrade); nothing of the reason is carried. */
    data object Failed : SocketOpening
}

/** An open socket. */
interface SocketLink {
    /** The next frame the server sent, or the end of the socket. A failure inside is the end ([SocketFrame.Closed] with no code). */
    suspend fun receive(): SocketFrame

    /** Closes the socket; safe to call twice. */
    suspend fun close()
}

sealed interface SocketFrame {
    class Text(
        val text: String,
    ) : SocketFrame

    /** The socket ended; [code] is the close code the server gave, or `null` if there was none (a dropped connection). */
    class Closed(
        val code: Int?,
    ) : SocketFrame
}
