package app.gifitup.shared.data.socket

import app.gifitup.protocol.SocketCloseCodes

/**
 * The close codes of the server's socket that the client acts on (ADR-0006 A2, ADR-0015). Ours come from `protocol/`; the
 * rest are the WebSocket standard's. The domain code reads them from here because it may not import the wire protocol.
 */
internal object CloseCodes {
    const val NORMAL: Int = 1000
    const val GOING_AWAY: Int = 1001
    const val POLICY_VIOLATION: Int = 1008
    const val CLIENT_OUTDATED: Int = SocketCloseCodes.CLIENT_OUTDATED
    const val SERVER_OUTDATED: Int = SocketCloseCodes.SERVER_OUTDATED
    const val BAD_HANDSHAKE: Int = SocketCloseCodes.BAD_HANDSHAKE
    const val UNAUTHORIZED: Int = SocketCloseCodes.UNAUTHORIZED
}
