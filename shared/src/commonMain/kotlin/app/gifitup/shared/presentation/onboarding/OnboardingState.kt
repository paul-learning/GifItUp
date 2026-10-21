package app.gifitup.shared.presentation.onboarding

import app.gifitup.shared.domain.auth.AuthError

/** The server a screen is about to talk to, always shown as the canonical ASCII origin (ADR-0013). Plain text only. */
class OriginView(
    val origin: String,
    /** Not the build's default: the UI shows a banner on every screen. */
    val custom: Boolean,
)

enum class InviteProblem {
    /** Not a code or a link of this app. */
    NOT_AN_INVITE,

    /** A well-formed link for another server: "not for this server", never a switch. */
    OTHER_SERVER,

    /** The server refused the invite (unknown, used, revoked or expired). */
    REFUSED,
}

enum class UsernameProblem {
    INVALID,

    /** The server says the name is taken or reserved: the only "taken" signal there is (no availability oracle). */
    TAKEN,
}

/** Why the device can't go on without the user's decision about its stored state (the screens come with the UI PR). */
enum class BlockReason {
    RESTORE_BLOCKED,
    KEY_UNAVAILABLE,
    STATE_INVALID,
}

/**
 * What the onboarding screens render. No state ever holds the invite code, a key, a token or a snapshot: the code lives
 * in the holder's memory only, and the classes here are plain data whose `toString` is the default (no secrets in it).
 */
sealed interface OnboardingState {
    data object Starting : OnboardingState

    class EnterInvite(
        val origin: OriginView,
        val problem: InviteProblem?,
        /** Only for [InviteProblem.OTHER_SERVER]: attacker-chosen text, rendered as inert text and never as a link. */
        val otherOrigin: String? = null,
    ) : OnboardingState

    class ChooseUsername(
        val origin: OriginView,
        val username: String,
        val problem: UsernameProblem?,
    ) : OnboardingState

    /** Shown before the first request that carries the invite code; nothing is sent until [OnboardingStateHolder.confirmRegistration]. */
    class ConfirmServer(
        val origin: OriginView,
        val username: String,
    ) : OnboardingState

    data object Registering : OnboardingState

    /** A registration whose outcome is unknown, or whose retry was refused: finish it, or discard it after a confirmation. */
    class FinishPending(
        val username: String,
        val lastError: AuthError?,
    ) : OnboardingState

    /**
     * Registered. [keyPackagesUploaded] is false if the upload just failed (the next start fills the pool with
     * `RefillKeyPackages`), and null when the device was registered earlier and nothing was checked now.
     */
    class Done(
        val username: String,
        val keyPackagesUploaded: Boolean?,
    ) : OnboardingState

    class Blocked(
        val reason: BlockReason,
    ) : OnboardingState

    /** Something failed that the user can only retry; [error] is a closed code mapped to fixed texts by the screens. */
    class Failed(
        val error: AuthError,
    ) : OnboardingState
}
