package app.gifitup.protocol

import kotlinx.serialization.Serializable

// Device authentication (ADR-0009). Byte fields are base64url without padding, each with one fixed decoded length; the
// server checks the raw string's length and alphabet before decoding. Times are Unix seconds. Every field is required:
// none has a default, so an older peer can't switch a check off by omitting one (ADR-0006 A7).

/** `POST /auth/challenge` → a 56-byte challenge, valid for 60 s and usable once. */
@Serializable
data class ChallengeResponse(
    val challenge: String,
)

/**
 * `POST /accounts`: registers an account and its first device. [signature] is the device's `signAuth(Register,
 * challenge, <the app's own origin>, username)` with the key in [signaturePublicKey]. [inviteCode] is exactly 22
 * characters of canonical base64url (16 bytes, ADR-0010), taken from the invite link's fragment; anything else is a 400.
 */
@Serializable
data class RegisterRequest(
    val username: String,
    val inviteCode: String,
    val signaturePublicKey: String,
    val challenge: String,
    val signature: String,
)

/** `POST /auth/login`: [signature] is `signAuth(Login, challenge, <the app's own origin>, deviceId)`. */
@Serializable
data class LoginRequest(
    val deviceId: String,
    val challenge: String,
    val signature: String,
)

/** A new session: send [sessionToken] as `Authorization: Bearer …` until [expiresAt], then log in again. */
@Serializable
data class SessionResponse(
    val deviceId: String,
    val sessionToken: String,
    val expiresAt: Long,
)

/** `GET /devices`: the caller's own devices. [lastSeenAt] has a resolution of one hour. */
@Serializable
data class DeviceListResponse(
    val devices: List<DeviceInfo>,
)

@Serializable
data class DeviceInfo(
    val deviceId: String,
    val createdAt: Long,
    val lastSeenAt: Long,
)
