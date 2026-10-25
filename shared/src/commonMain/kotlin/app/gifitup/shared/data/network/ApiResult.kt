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

    /** Claim: no such member (an unknown username and the caller's own look the same, ADR-0012). */
    NOT_FOUND,

    /** Upload: some KeyPackage is not acceptable; the server says no more than that (ADR-0012). */
    KEY_PACKAGE_INVALID,

    /** Upload: the device would hold more KeyPackages than the server allows. */
    KEY_PACKAGE_LIMIT,

    /** Create invite: already 5 open invites (ADR-0010; the daily cap of 20 is `RATE_LIMITED`). */
    INVITE_LIMIT,

    /** Delivery Service (ADR-0015): the envelope broke a rule; the server says no more than that. */
    ENVELOPE_INVALID,

    /** A repeated client message id came with a different envelope. */
    ENVELOPE_CONFLICT,

    /** A commit's epoch is not the group's current epoch: fetch the group's newer messages and retry. */
    EPOCH_CONFLICT,

    /** The group's retained log is at its limit until members acknowledge. */
    GROUP_FULL,

    /** The target device holds too many pending welcomes, or was added too often. */
    WELCOME_LIMIT,

    /** The device is at its group or membership limit. */
    GROUP_LIMIT,

    /** A payload is over its cap. */
    PAYLOAD_TOO_LARGE,
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

/** The answer to an upload: how many were stored, how many this device now has, and when to refill (ADR-0012). */
data class KeyPackageUpload(
    val accepted: Int,
    val available: Int,
    val lowWaterMark: Int,
)

data class KeyPackageStatus(
    val available: Int,
    val lowWaterMark: Int,
)

/** What a claim returned: the claimed account's id (untrusted, a canonical UUID) and its devices, oldest first. */
class ClaimedKeyPackages(
    val accountId: String,
    val devices: List<ClaimedKeyPackage>,
)

/** One device of the claimed member; [keyPackage] is null if it had none for the wanted ciphersuites. Untrusted bytes. */
class ClaimedKeyPackage(
    val deviceId: String,
    val keyPackage: ByteArray?,
)

/** A new invite as the server answered. [link] is untrusted: the client checks it before anyone sees it (ADR-0013). */
class CreatedInviteAnswer(
    val inviteId: String,
    val link: String,
    val expiresAt: Long,
) {
    override fun toString(): String = "CreatedInviteAnswer(inviteId=$inviteId, expiresAt=$expiresAt)"
}

/**
 * One of the member's own invites (ADR-0010). Times are Unix seconds; [usedBy] is the username that redeemed it, or
 * null on a used invite whose account was deleted since.
 */
data class Invite(
    val inviteId: String,
    val createdAt: Long,
    val expiresAt: Long,
    val status: InviteState,
    val usedBy: String?,
)

/**
 * An invite's state as the app sees it; the wire enum stays in `protocol`, which the UI never imports. [UNKNOWN] is a
 * state a newer server sends that this build doesn't know (ADR-0006): shown as "not usable".
 */
enum class InviteState { OPEN, USED, EXPIRED, REVOKED, UNKNOWN }
