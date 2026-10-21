package app.gifitup.shared.presentation.onboarding

import app.gifitup.shared.data.network.ApiError
import app.gifitup.shared.data.storage.IdentityStore
import app.gifitup.shared.data.storage.LocalStateException
import app.gifitup.shared.data.storage.StoredIdentity
import app.gifitup.shared.domain.auth.AuthError
import app.gifitup.shared.domain.origin.ServerOriginSetting

/** The state decisions of [OnboardingStateHolder] that read stored state; no flow of its own. */
internal class OnboardingTransitions(
    private val origin: ServerOriginSetting,
    private val identities: IdentityStore,
) {
    /** What the stored identity says decides: a kept pending identity is finished or discarded, a cleared one retried. */
    suspend fun stateAfterFailedRegistration(
        error: AuthError,
        name: String,
    ): OnboardingState {
        val pending = readIdentity()
        return when {
            pending is Read.Found && pending.identity.deviceId == null -> OnboardingState.FinishPending(pending.identity.username, error)
            error == AuthError.Api(ApiError.USERNAME_UNAVAILABLE) -> usernameState(name, UsernameProblem.TAKEN)
            error == AuthError.Api(ApiError.INVITE_INVALID) -> enterInviteState(InviteProblem.REFUSED)
            else -> failureState(error)
        }
    }

    suspend fun firstState(): OnboardingState {
        val identity = readIdentity()
        return when {
            identity is Read.Damaged -> {
                OnboardingState.Blocked(BlockReason.STATE_INVALID)
            }

            identity is Read.Found && identity.identity.deviceId != null -> {
                OnboardingState.Done(
                    identity.identity.username,
                    keyPackagesUploaded = true,
                )
            }

            identity is Read.Found -> {
                OnboardingState.FinishPending(identity.identity.username, lastError = null)
            }

            else -> {
                enterInviteState()
            }
        }
    }

    suspend fun enterInviteState(problem: InviteProblem? = null): OnboardingState =
        currentOrigin()?.let { OnboardingState.EnterInvite(it, problem) } ?: OnboardingState.Blocked(BlockReason.STATE_INVALID)

    suspend fun usernameState(
        name: String,
        problem: UsernameProblem,
    ): OnboardingState =
        currentOrigin()?.let { OnboardingState.ChooseUsername(it, name, problem) } ?: OnboardingState.Blocked(BlockReason.STATE_INVALID)

    suspend fun currentOrigin(): OriginView? =
        (origin.current() as? ServerOriginSetting.Current.Origin)?.let { OriginView(it.origin, it.custom) }

    fun failureState(error: AuthError): OnboardingState =
        when (error) {
            AuthError.Local.RESTORE_BLOCKED -> OnboardingState.Blocked(BlockReason.RESTORE_BLOCKED)
            AuthError.Local.KEY_UNAVAILABLE -> OnboardingState.Blocked(BlockReason.KEY_UNAVAILABLE)
            AuthError.Local.STATE_INVALID -> OnboardingState.Blocked(BlockReason.STATE_INVALID)
            else -> OnboardingState.Failed(error)
        }

    private sealed interface Read {
        class Found(
            val identity: StoredIdentity,
        ) : Read

        data object None : Read

        data object Damaged : Read
    }

    private suspend fun readIdentity(): Read =
        try {
            identities.load()?.let { Read.Found(it) } ?: Read.None
        } catch (e: LocalStateException) {
            if (e.problem == LocalStateException.Problem.DAMAGED) Read.Damaged else throw e
        }
}
