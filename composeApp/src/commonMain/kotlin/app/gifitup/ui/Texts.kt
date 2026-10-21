package app.gifitup.ui

import app.gifitup.shared.data.network.ApiError
import app.gifitup.shared.domain.auth.AuthError
import app.gifitup.shared.presentation.onboarding.BlockReason
import app.gifitup.shared.presentation.onboarding.InviteProblem
import app.gifitup.shared.presentation.onboarding.UsernameProblem

/** Fixed texts for closed codes: the UI never shows server text or an exception message (ADR-0006 A8). */
internal object Texts {
    fun invite(problem: InviteProblem): String =
        when (problem) {
            InviteProblem.NOT_AN_INVITE -> "That is not an invite code or an invite link."
            InviteProblem.OTHER_SERVER -> "This invite is for another server. It can't be used here."
            InviteProblem.REFUSED -> "The server didn't accept this invite (unknown, used, revoked or expired)."
        }

    fun username(problem: UsernameProblem): String =
        when (problem) {
            UsernameProblem.INVALID -> "3 to 32 characters: a–z, 0–9 and _."
            UsernameProblem.TAKEN -> "That username is taken. Try another one."
        }

    fun blocked(reason: BlockReason): String =
        when (reason) {
            BlockReason.RESTORE_BLOCKED -> "This device's data couldn't be opened several times in a row."
            BlockReason.KEY_UNAVAILABLE -> "This device's key can't be used right now."
            BlockReason.STATE_INVALID -> "This device's data is damaged."
        }

    fun error(error: AuthError): String =
        when (error) {
            is AuthError.Api -> api(error.error)
            AuthError.Local.STORAGE_FAILED -> "The device's storage failed. Try again."
            AuthError.Local.CRYPTO -> "Encryption failed on this device."
            else -> "Something went wrong on this device."
        }

    private fun api(error: ApiError): String =
        when (error) {
            ApiError.NETWORK -> "No answer from the server. Check the connection and try again."
            ApiError.RATE_LIMITED -> "Too many attempts. Try again later."
            ApiError.CLIENT_OUTDATED -> "This app is too old for the server. Please update."
            ApiError.SERVER_OUTDATED -> "The server is older than this app."
            else -> "The server refused the request."
        }
}
