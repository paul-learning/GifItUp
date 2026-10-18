package app.gifitup.shared.data.network

/** Outcome of one API call. A failure carries a closed [ApiError] and nothing else: no server text, no exception. */
sealed interface ApiResult<out T> {
    class Ok<T>(
        val value: T,
    ) : ApiResult<T>

    data class Failed(
        val error: ApiError,
    ) : ApiResult<Nothing>
}

/**
 * Why a call failed. Server answers map by their closed `ApiErrorCode` (ADR-0006 A8); codes an endpoint has no use for
 * become [SERVER_ERROR]. The client adds [MALFORMED_RESPONSE] (an answer that breaks the protocol: wrong status, a body
 * that doesn't decode or fails a field check, a redirect, or over the size cap) and [NETWORK] (no answer at all).
 */
enum class ApiError {
    BAD_REQUEST,
    CLIENT_OUTDATED,
    SERVER_OUTDATED,
    UNAUTHORIZED,
    INVITE_INVALID,
    USERNAME_UNAVAILABLE,
    RATE_LIMITED,
    SERVER_ERROR,
    MALFORMED_RESPONSE,
    NETWORK,
}

/**
 * A logged-in session (ADR-0009): valid until [expiresAt] (Unix seconds), then log in again. Kept in memory only, never
 * stored (#79 plan). The token never appears in [toString]. Bound to the [endpoint] that issued it (#94 SEC-13).
 */
class Session internal constructor(
    val deviceId: String,
    internal val token: String,
    val expiresAt: Long,
    internal val endpoint: ApiEndpoint,
) {
    override fun toString(): String = "Session(deviceId=$deviceId, expiresAt=$expiresAt)"
}

/** One of the account's own devices. Times are Unix seconds; [lastSeenAt] has a resolution of one hour. */
data class Device(
    val deviceId: String,
    val createdAt: Long,
    val lastSeenAt: Long,
)
