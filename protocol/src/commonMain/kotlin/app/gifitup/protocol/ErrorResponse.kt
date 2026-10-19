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

    /** No valid session, or a challenge, signature or device that doesn't check out. Deliberately one code for all. */
    UNAUTHORIZED("unauthorized"),

    /** The invite code was not accepted (unknown, used, revoked or expired; deliberately one code for all). */
    INVITE_INVALID("invite_invalid"),

    /** The username is taken or reserved. */
    USERNAME_UNAVAILABLE("username_unavailable"),

    /** Too many requests; try again later. */
    RATE_LIMITED("rate_limited"),

    /** The caller already has the maximum number of open invites (ADR-0010); one must be used, revoked or expire. */
    INVITE_LIMIT("invite_limit"),

    /** A KeyPackage in an upload is not acceptable (ADR-0012). One code for every reason; the server logs the reason. */
    KEY_PACKAGE_INVALID("key_package_invalid"),

    /** The device would hold more KeyPackages than the directory allows (ADR-0012); nothing was stored. */
    KEY_PACKAGE_LIMIT("key_package_limit"),

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
