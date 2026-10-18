package app.gifitup.shared.domain.auth

import app.gifitup.shared.data.crypto.MlsErrorCode
import app.gifitup.shared.data.crypto.MlsException
import app.gifitup.shared.data.network.ApiError
import app.gifitup.shared.data.network.ApiResult
import app.gifitup.shared.data.network.Base64Url

/** Outcome of an auth use case. Failures are closed codes only, so a caller can map each to its own fixed text. */
sealed interface AuthResult<out T> {
    class Ok<T>(
        val value: T,
    ) : AuthResult<T>

    data class Failed(
        val error: AuthError,
    ) : AuthResult<Nothing>
}

sealed interface AuthError {
    /** The server refused, or answered outside the protocol, or didn't answer. */
    data class Api(
        val error: ApiError,
    ) : AuthError

    /** Refused on this device, before or instead of a request. */
    enum class Local : AuthError {
        INVALID_USERNAME,
        INVALID_INVITE_CODE,

        /** This device already has a registered identity. */
        ALREADY_REGISTERED,

        /** Login without a registered identity. */
        NOT_REGISTERED,

        /** A pending registration exists under another username; it can only be resumed as itself. */
        USERNAME_MISMATCH,

        /** The stored identity belongs to another origin than the configured one. */
        OTHER_ORIGIN,

        /** The stored identity or its key is missing, damaged or doesn't match. */
        STATE_INVALID,

        /** The MLS client failed (`MlsException`). */
        CRYPTO,
    }
}

/** Checks mirrored from the server, so a bad input never costs a request. The server stays authoritative. */
internal object InputRules {
    private val USERNAME = Regex("^[a-z0-9_]{3,32}$")
    private const val INVITE_CODE_BYTES = 16

    fun isValidUsername(username: String): Boolean = USERNAME.matches(username)

    /** Exactly 22 characters of canonical base64url (16 bytes, ADR-0010). */
    fun isValidInviteCode(code: String): Boolean = Base64Url.decode(code, INVITE_CODE_BYTES..INVITE_CODE_BYTES) != null
}

internal const val SNAPSHOT_KEY_SIZE = 32

internal fun failed(error: AuthError.Local): AuthResult.Failed = AuthResult.Failed(error)

internal fun <T> ApiResult<T>.toAuth(): AuthResult<T> =
    when (this) {
        is ApiResult.Ok -> AuthResult.Ok(value)
        is ApiResult.Failed -> AuthResult.Failed(AuthError.Api(error))
    }

/** Runs the next step only after a success. */
internal inline fun <T, R> ApiResult<T>.then(next: (T) -> ApiResult<R>): ApiResult<R> =
    when (this) {
        is ApiResult.Ok -> next(value)
        is ApiResult.Failed -> this
    }

/** Snapshot problems mean the stored state is unusable; anything else is a crypto failure. */
internal fun MlsException.toAuthFailure(): AuthResult.Failed =
    when (code) {
        MlsErrorCode.SnapshotInvalid, MlsErrorCode.SnapshotVersion, MlsErrorCode.SnapshotAuth -> failed(AuthError.Local.STATE_INVALID)
        else -> failed(AuthError.Local.CRYPTO)
    }
