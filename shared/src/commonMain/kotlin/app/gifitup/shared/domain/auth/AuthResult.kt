package app.gifitup.shared.domain.auth

import app.gifitup.shared.data.crypto.MlsErrorCode
import app.gifitup.shared.data.crypto.MlsException
import app.gifitup.shared.data.network.ApiError
import app.gifitup.shared.data.network.ApiResult
import app.gifitup.shared.data.network.Base64Url
import app.gifitup.shared.data.storage.LocalStateException

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

        /**
         * The stored snapshot key can't be unwrapped right now: the platform key is missing or refused (ADR-0011). Can
         * be transient; it is never counted as a failed restore (#16 SEC-4).
         */
        KEY_UNAVAILABLE,

        /** The last restores trapped or never returned ([GuardedMlsFactory]); the user chooses "try again" or a reset. */
        RESTORE_BLOCKED,

        /** The device's storage failed to read or write; can be transient (#108 SEC-13). */
        STORAGE_FAILED,
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

/** A store's or the restore guard's refusal, as a closed result (no message, no cause). */
internal fun LocalStateException.toAuthFailure(): AuthResult.Failed =
    when (problem) {
        LocalStateException.Problem.DAMAGED -> failed(AuthError.Local.STATE_INVALID)
        LocalStateException.Problem.KEY_UNAVAILABLE -> failed(AuthError.Local.KEY_UNAVAILABLE)
        LocalStateException.Problem.STORAGE -> failed(AuthError.Local.STORAGE_FAILED)
    }

/** Snapshot problems mean the stored state is unusable; anything else is a crypto failure. */
internal fun MlsException.toAuthFailure(): AuthResult.Failed =
    when (code) {
        MlsErrorCode.SnapshotInvalid, MlsErrorCode.SnapshotVersion, MlsErrorCode.SnapshotAuth -> failed(AuthError.Local.STATE_INVALID)
        else -> failed(AuthError.Local.CRYPTO)
    }
