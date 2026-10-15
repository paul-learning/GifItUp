package app.gifitup.protocol

import kotlinx.serialization.Serializable

/** Body of `GET /health`. Reveals nothing beyond liveness and the supported protocol version range. */
@Serializable
data class HealthResponse(
    val status: String,
    val protocolVersion: Int,
    val minProtocolVersion: Int = MIN_SUPPORTED_PROTOCOL_VERSION,
)
