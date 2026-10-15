package app.gifitup.protocol

import kotlinx.serialization.Serializable

/** Body of `GET /health`. Reveals nothing beyond liveness and the supported protocol version range. */
@Serializable
data class HealthResponse(
    val status: String,
    val protocolVersion: Int,
    /** Added after protocol 1 shipped. Servers that omit it only ever supported version 1, so the default is a literal. */
    val minProtocolVersion: Int = 1,
)
