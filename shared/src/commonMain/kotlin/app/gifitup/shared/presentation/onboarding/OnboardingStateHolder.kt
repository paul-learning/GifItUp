package app.gifitup.shared.presentation.onboarding

import app.gifitup.shared.data.network.ApiError
import app.gifitup.shared.data.network.Session
import app.gifitup.shared.data.storage.IdentityStore
import app.gifitup.shared.data.storage.LocalStateException
import app.gifitup.shared.data.storage.StoredIdentity
import app.gifitup.shared.domain.auth.AuthError
import app.gifitup.shared.domain.auth.AuthResult
import app.gifitup.shared.domain.auth.InputRules
import app.gifitup.shared.domain.auth.RegisterDevice
import app.gifitup.shared.domain.invite.InviteCode
import app.gifitup.shared.domain.invite.InviteInput
import app.gifitup.shared.domain.invite.InviteLinks
import app.gifitup.shared.domain.keypackages.RefillKeyPackages
import app.gifitup.shared.domain.origin.ServerOriginSetting
import app.gifitup.shared.domain.session.SessionHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The onboarding flow as a state machine (#17, ADR-0013): invite, username, an explicit server confirmation, register,
 * upload the first KeyPackages. Screens render [state] and call the functions; nothing else decides anything.
 *
 * - **Nothing leaves the device before [confirmRegistration]**: no request and no key while the user is typing, and
 *   username checks are local only (no availability oracle; the register answer is the only "taken" signal).
 * - **The invite code lives in this holder's memory only** (an [InviteCode], never in a state, a route or saved state)
 *   and is dropped as soon as the registration ended.
 * - **After any unclear registration** (lost reply, refused retry) the stored pending identity decides the next
 *   state: [OnboardingState.FinishPending], where [finishPending] resumes without a code and [discardPending]
 *   needs `confirmed = true` (the screen's irreversible-action confirmation).
 * - **The first-registration confirmation gates the use case, not only the screen**: [confirmRegistration] only acts
 *   from [OnboardingState.ConfirmServer], so a stray call in another state does nothing.
 */
class OnboardingStateHolder(
    private val scope: CoroutineScope,
    origin: ServerOriginSetting,
    identities: IdentityStore,
    private val register: RegisterDevice,
    private val refill: RefillKeyPackages,
    private val sessions: SessionHolder,
) {
    private val transitions = OnboardingTransitions(origin, identities)
    private val flow = MutableStateFlow<OnboardingState>(OnboardingState.Starting)
    val state: StateFlow<OnboardingState> = flow.asStateFlow()

    private var invite: InviteCode? = null
    private var username: String = ""

    /** Decides the first screen from what the device already holds. */
    fun start() {
        scope.launch { flow.value = transitions.safely { transitions.firstState() } }
    }

    fun submitInvite(text: String) {
        if (flow.value !is OnboardingState.EnterInvite) return
        scope.launch {
            val current = transitions.currentOrigin() ?: return@launch

            fun accept(
                code: InviteCode,
                view: OriginView,
            ) {
                invite = code
                flow.value = OnboardingState.ChooseUsername(view, username, problem = null)
            }
            when (val parsed = InviteLinks.parse(text, current.origin)) {
                is InviteInput.Code -> accept(parsed.code, current)
                is InviteInput.Link -> accept(parsed.code, current)
                is InviteInput.OtherOrigin -> flow.value = OnboardingState.EnterInvite(current, InviteProblem.OTHER_SERVER, parsed.origin)
                InviteInput.Invalid -> flow.value = OnboardingState.EnterInvite(current, InviteProblem.NOT_AN_INVITE)
            }
        }
    }

    /** Local validation only; asks nobody. */
    fun changeUsername(text: String) {
        val current = flow.value as? OnboardingState.ChooseUsername ?: return
        flow.value = OnboardingState.ChooseUsername(current.origin, text, problem = null)
    }

    fun submitUsername() {
        val current = flow.value as? OnboardingState.ChooseUsername ?: return
        flow.value =
            if (InputRules.isValidUsername(current.username)) {
                username = current.username
                OnboardingState.ConfirmServer(current.origin, current.username)
            } else {
                OnboardingState.ChooseUsername(current.origin, current.username, UsernameProblem.INVALID)
            }
    }

    /** Back from the confirmation to the username (nothing was sent). */
    fun backToUsername() {
        val current = flow.value as? OnboardingState.ConfirmServer ?: return
        flow.value = OnboardingState.ChooseUsername(current.origin, current.username, problem = null)
    }

    fun confirmRegistration() {
        val confirm = flow.value as? OnboardingState.ConfirmServer
        val code = invite
        // One claim: a double tap finds the state already changed and does nothing.
        if (confirm == null || code == null || !flow.compareAndSet(confirm, OnboardingState.Registering)) return
        scope.launch {
            val mark = sessions.mark()
            val current = transitions.currentOrigin()?.origin
            if (current != confirm.origin.origin || current != register.origin) {
                // The server the user confirmed is not the one the request would go to: nothing is sent.
                invite = null
                flow.value = transitions.safely { transitions.enterInviteState(InviteProblem.NOT_AN_INVITE) }
            } else {
                afterRegistration(register(confirm.username, code.reveal()), confirm.username, mark)
            }
        }
    }

    fun finishPending() {
        val pending = flow.value as? OnboardingState.FinishPending ?: return
        if (!flow.compareAndSet(pending, OnboardingState.Registering)) return
        scope.launch { afterRegistration(register.resume(), pending.username, sessions.mark()) }
    }

    /** Irreversible: the earlier attempt may still have created the account. Only with the user's confirmation. */
    fun discardPending(confirmed: Boolean) {
        val pending = flow.value as? OnboardingState.FinishPending ?: return
        if (!confirmed || !flow.compareAndSet(pending, OnboardingState.Registering)) return
        scope.launch {
            flow.value =
                transitions.safely {
                    when (val result = register.discardPending()) {
                        is AuthResult.Ok -> transitions.enterInviteState()
                        is AuthResult.Failed -> transitions.failureState(result.error)
                    }
                }
        }
    }

    private suspend fun afterRegistration(
        result: AuthResult<Session>,
        name: String,
        mark: Int,
    ) {
        val next =
            transitions.safely {
                when (result) {
                    is AuthResult.Ok -> finishWith(result.value, name, mark)
                    is AuthResult.Failed -> transitions.stateAfterFailedRegistration(result.error, name)
                }
            }
        // A taken username leaves the invite unused (ADR-0009): keep the code for the next try. Every other end drops it.
        if (next !is OnboardingState.ChooseUsername) invite = null
        flow.value = next
    }

    private suspend fun finishWith(
        session: Session,
        name: String,
        mark: Int,
    ): OnboardingState {
        sessions.adopt(session, mark)
        val uploaded = refill(session) is AuthResult.Ok
        return OnboardingState.Done(name, uploaded)
    }
}
