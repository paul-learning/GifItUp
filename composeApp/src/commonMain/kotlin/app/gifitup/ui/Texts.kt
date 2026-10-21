package app.gifitup.ui

import app.gifitup.shared.data.network.ApiError
import app.gifitup.shared.data.network.Invite
import app.gifitup.shared.data.network.InviteState
import app.gifitup.shared.domain.auth.AuthError
import app.gifitup.shared.presentation.invite.InviteScreenProblem
import app.gifitup.shared.presentation.onboarding.BlockReason
import app.gifitup.shared.presentation.onboarding.InviteProblem
import app.gifitup.shared.presentation.onboarding.UsernameProblem
import app.gifitup.shared.presentation.settings.ServerSettingsProblem

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

    fun invite(problem: InviteScreenProblem): String =
        when (problem) {
            InviteScreenProblem.OPEN_LIMIT -> "You have the most open invites allowed. Revoke one, or wait until one is used or expires."
            InviteScreenProblem.RATE_LIMITED -> "Too many invites created today. Try again later."
            InviteScreenProblem.OFFLINE -> "No answer from the server. If an invite was created, it is in the list below."
            InviteScreenProblem.NOT_SIGNED_IN -> "The server didn't accept this device."
            InviteScreenProblem.COPY_FAILED -> "Couldn't copy. Select the link and copy it by hand."
            InviteScreenProblem.FAILED -> "Something went wrong. Try again."
        }

    fun copied(clearsClipboard: Boolean): String =
        if (clearsClipboard) {
            "Copied. The link is removed from the clipboard when you leave this screen."
        } else {
            "Copied. The link stays on the clipboard until you copy something else."
        }

    /** One line per invite: its state, when it expires, who used it. Plain text from closed values and a validated username. */
    fun inviteRow(invite: Invite): String =
        when (invite.status) {
            InviteState.OPEN -> "Open, expires ${invite.expiryText()}"
            InviteState.USED -> "Used by ${invite.usedBy ?: "an account that was deleted"}"
            InviteState.EXPIRED -> "Expired"
            InviteState.REVOKED -> "Revoked"
            InviteState.UNKNOWN -> "Not usable"
        }

    fun settings(problem: ServerSettingsProblem): String =
        when (problem) {
            ServerSettingsProblem.INVALID -> "Enter https:// and the server's name, nothing after it."
            ServerSettingsProblem.HAS_IDENTITY -> "This device is registered with its server; it can't switch."
            ServerSettingsProblem.STORAGE_FAILED -> "The device's storage failed. Try again."
        }

    /** Every case is listed, so a new error code can't fall into a vague text unnoticed (#131 CR-5). */
    fun error(error: AuthError): String =
        when (error) {
            is AuthError.Api -> api(error.error)
            AuthError.Local.STORAGE_FAILED -> "The device's storage failed. Try again."
            AuthError.Local.CRYPTO -> "Encryption failed on this device."
            AuthError.Local.KEY_UNAVAILABLE -> "This device's key can't be used right now."
            AuthError.Local.RESTORE_BLOCKED -> "This device's data couldn't be opened several times in a row."
            AuthError.Local.STATE_INVALID -> "This device's data is damaged."
            AuthError.Local.NOT_REGISTERED -> "This device isn't registered."
            AuthError.Local.ALREADY_REGISTERED -> "This device is already registered."
            AuthError.Local.OTHER_ORIGIN -> "This device is registered with another server."
            AuthError.Local.USERNAME_MISMATCH -> "An unfinished registration for another username is on this device."
            AuthError.Local.INVALID_USERNAME -> "3 to 32 characters: a–z, 0–9 and _."
            AuthError.Local.INVALID_INVITE_CODE -> "That is not an invite code."
            AuthError.Local.UNEXPECTED -> "Something went wrong on this device."
        }

    private fun api(error: ApiError): String =
        when (error) {
            ApiError.NETWORK -> {
                "No answer from the server. Check the connection and try again."
            }

            ApiError.RATE_LIMITED -> {
                "Too many attempts. Try again later."
            }

            ApiError.CLIENT_OUTDATED -> {
                "This app is too old for the server. Please update."
            }

            ApiError.SERVER_OUTDATED -> {
                "The server is older than this app."
            }

            ApiError.INVITE_INVALID -> {
                "The server didn't accept this invite."
            }

            ApiError.USERNAME_UNAVAILABLE -> {
                "That username is taken."
            }

            ApiError.UNAUTHORIZED -> {
                "The server didn't accept this device."
            }

            ApiError.SERVER_ERROR, ApiError.MALFORMED_RESPONSE -> {
                "The server failed. Try again later."
            }

            ApiError.INVITE_LIMIT -> {
                "You already have 5 open invites. Revoke one, or wait until one is used or expires."
            }

            ApiError.BAD_REQUEST, ApiError.NOT_FOUND, ApiError.KEY_PACKAGE_INVALID, ApiError.KEY_PACKAGE_LIMIT -> {
                "The server refused the request."
            }
        }
}
