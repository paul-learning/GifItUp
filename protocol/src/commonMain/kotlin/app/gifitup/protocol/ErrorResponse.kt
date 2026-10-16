package app.gifitup.protocol

import kotlinx.serialization.Serializable

/**
 * Closed error codes in error response bodies (ADR-0006 A4, A8). The set can grow, so clients decode an unknown code as
 * [UNKNOWN] and treat it as a generic failure. The server never sends free text: a code is all a client learns.
 */
@Serializable(with = ApiErrorCodeSerializer::class)
enum class ApiErrorCode(
    val wire: String,
) {
    /** Malformed or invalid request (bad JSON, failed validation, an `UNKNOWN` enum value). */
    BAD_REQUEST("bad_request"),

    /** Protocol header missing, malformed or below the minimum; see [ClientOutdatedResponse]. */
    CLIENT_OUTDATED("client_outdated"),

    /** The client speaks a newer protocol than this server. */
    SERVER_OUTDATED("server_outdated"),
    NOT_FOUND("not_found"),
    METHOD_NOT_ALLOWED("method_not_allowed"),
    PAYLOAD_TOO_LARGE("payload_too_large"),
    UNSUPPORTED_MEDIA_TYPE("unsupported_media_type"),
    INTERNAL("internal"),

    /** A code this build doesn't know. Never sent. */
    UNKNOWN("unknown"),
}

internal object ApiErrorCodeSerializer : FallbackEnumSerializer<ApiErrorCode>(
    serialName = "app.gifitup.protocol.ApiErrorCode",
    values = ApiErrorCode.entries,
    fallback = ApiErrorCode.UNKNOWN,
    wireName = ApiErrorCode::wire,
)

/** Body of every error response, except [ClientOutdatedResponse]. Example: `{"error":"bad_request"}`. */
@Serializable
data class ErrorResponse(
    val error: ApiErrorCode,
)

/**
 * Body of the `client_outdated` error (ADR-0006 A4): `{"error":"client_outdated","minProtocolVersion":n}`. The value is
 * untrusted on the client: it bounds it and only ever shows its own built-in update text (A5).
 */
@Serializable
data class ClientOutdatedResponse(
    val error: ApiErrorCode,
    val minProtocolVersion: Int,
)
