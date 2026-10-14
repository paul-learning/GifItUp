package app.gifitup.protocol

import kotlinx.serialization.Serializable

/** Body of `GET /health`. Reveals nothing beyond liveness and the protocol version. */
@Serializable
data class HealthResponse(
    val status: String,
    val protocolVersion: Int,
)
