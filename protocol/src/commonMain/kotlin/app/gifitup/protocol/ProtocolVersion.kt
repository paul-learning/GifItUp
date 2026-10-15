package app.gifitup.protocol

/**
 * Version of the client/server wire protocol. Bump on any change that is not backward compatible (ADR-0006).
 */
const val PROTOCOL_VERSION: Int = 1

/**
 * Oldest client protocol version the server still serves. Raising it forces clients to update, so it is an owner
 * decision (ADR-0006).
 */
const val MIN_SUPPORTED_PROTOCOL_VERSION: Int = 1

/** HTTP header in which clients send their [PROTOCOL_VERSION] on every request (ADR-0006; checked from #12). */
const val PROTOCOL_VERSION_HEADER: String = "GifItUp-Protocol"
