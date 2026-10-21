package app.gifitup.shared.presentation.invite

import app.gifitup.shared.data.network.ApiError
import app.gifitup.shared.data.network.Invite
import app.gifitup.shared.domain.auth.AuthError
import app.gifitup.shared.domain.auth.AuthResult
import app.gifitup.shared.domain.invite.CreateInvite
import app.gifitup.shared.domain.invite.ListInvites
import app.gifitup.shared.domain.invite.RevokeInvite
import app.gifitup.shared.domain.session.SessionHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * The platform's clipboard for a live invite link (ADR-0013, #17 plan review SEC-8). The platform marks the entry
 * sensitive where it can (Android 13 and up), and [clearCopied] removes it again without ever reading the clipboard's
 * content. A platform that can't do that says so by doing nothing; the screen tells the user.
 */
interface SensitiveClipboard {
    /** Puts [text] on the clipboard; false if the platform refused. */
    suspend fun copy(text: String): Boolean

    /** Removes what [copy] put there, if it is still there. Never reads the content. Not suspending: it runs while the screen is left. */
    fun clearCopied()
}

/** An invite just created. The link is a live credential: it is on screen only, and `toString` leaves it out. */
class ShownInvite internal constructor(
    val inviteId: String,
    val link: String,
    val expiresAt: Long,
) {
    override fun toString(): String = "ShownInvite(inviteId=$inviteId, expiresAt=$expiresAt)"
}

/** Why the last action failed; the screen maps each to a fixed text. */
enum class InviteScreenProblem {
    /** The account already has the most open invites the server allows (ADR-0010). */
    OPEN_LIMIT,

    /** Too many invites created today, or too many requests. */
    RATE_LIMITED,

    /** No answer. A create may still have gone through: the list shows it. */
    OFFLINE,

    /** The server doesn't know this device or the session can't be renewed. */
    NOT_SIGNED_IN,

    /** The platform refused to copy. */
    COPY_FAILED,

    FAILED,
}

/** What the create-invite screen renders. [invites] is null until the first list arrived. */
data class InviteScreenState(
    val invites: List<Invite>? = null,
    val busy: Boolean = false,
    val shown: ShownInvite? = null,
    val copied: Boolean = false,
    val problem: InviteScreenProblem? = null,
)

/**
 * The create-invite screen's logic (#17, ADR-0013, ADR-0010). One action at a time; every request goes through the
 * [SessionHolder], so a rejected session is renewed once. The new link comes from [CreateInvite], which accepts only
 * a link for the app's own origin and revokes any other.
 *
 * The state is updated on the caller's single-threaded dispatcher (a `viewModelScope`); the copy check is atomic anyway.
 *
 * The link lives in [state] until the user creates another one, revokes it, or the screen is left ([leave]); then it
 * is dropped from memory and, if it was copied, removed from the clipboard. A create that was started before the
 * screen was left drops its answer: nobody sees the link, and the invite shows in the list as open.
 */
class CreateInviteStateHolder(
    private val scope: CoroutineScope,
    private val sessions: SessionHolder,
    private val createInvite: CreateInvite,
    private val listInvites: ListInvites,
    private val revokeInvite: RevokeInvite,
    private val clipboard: SensitiveClipboard,
) {
    private val flow = MutableStateFlow(InviteScreenState())
    val state: StateFlow<InviteScreenState> = flow.asStateFlow()

    /** Bumped by [leave]: an answer for an older screen visit is dropped. */
    private var visit = 0

    fun refresh() = act { loadList() }

    /** Never repeated on its own: a lost answer may mean an invite exists that the user never saw (the list shows it). */
    fun create() {
        val forVisit = visit
        act {
            dropShown()
            when (val created = sessions.withSessionResult { createInvite(it) }) {
                is AuthResult.Ok -> {
                    val invite = created.value
                    if (forVisit == visit) flow.update { it.copy(shown = ShownInvite(invite.inviteId, invite.link, invite.expiresAt)) }
                }

                is AuthResult.Failed -> {
                    flow.update { it.copy(problem = created.error.toProblem()) }
                }
            }
            loadList()
        }
    }

    fun copy() {
        val shown = flow.value.shown ?: return
        scope.launch {
            val copied = safeCopy(shown.link)
            var stillShown = false
            flow.update {
                stillShown = it.shown === shown
                if (stillShown) it.copy(copied = copied, problem = if (copied) it.problem else InviteScreenProblem.COPY_FAILED) else it
            }
            if (!stillShown && copied) clipboard.clearCopied() // the link was dropped while the platform copied it
        }
    }

    /** A platform failure is a refusal, not a crash; only our own cancellation goes on. */
    @Suppress("TooGenericExceptionCaught") // Anything but cancellation becomes COPY_FAILED; no message is kept.
    private suspend fun safeCopy(text: String): Boolean =
        try {
            clipboard.copy(text)
        } catch (e: CancellationException) {
            throw e
        } catch (ignored: Throwable) {
            false
        }

    fun revoke(inviteId: String) =
        act {
            val revoked = sessions.withSessionResult { revokeInvite(it, inviteId) }
            when {
                revoked is AuthResult.Ok -> {}

                // Used, expired or revoked already: nothing to undo, the list shows the truth.
                revoked is AuthResult.Failed && revoked.error == AuthError.Api(ApiError.NOT_FOUND) -> {}

                revoked is AuthResult.Failed -> {
                    flow.update { it.copy(problem = revoked.error.toProblem()) }
                }
            }
            if (flow.value.shown?.inviteId == inviteId && flow.value.problem == null) dropShown()
            loadList()
        }

    /** The screen was left: drop the link and take it off the clipboard. Synchronous, so it also works while the owner is cleared. */
    fun leave() {
        visit++
        val copied = flow.value.copied
        flow.update { it.copy(shown = null, copied = false, problem = null) }
        if (copied) clipboard.clearCopied()
    }

    private fun dropShown() {
        val copied = flow.value.copied
        flow.update { it.copy(shown = null, copied = false) }
        if (copied) clipboard.clearCopied()
    }

    private suspend fun loadList() {
        when (val listed = sessions.withSessionResult { listInvites(it) }) {
            is AuthResult.Ok -> flow.update { it.copy(invites = listed.value) }
            is AuthResult.Failed -> flow.update { it.copy(problem = it.problem ?: listed.error.toProblem()) }
        }
    }

    /** Runs [block] unless another action is running; the busy flag is set before this returns. */
    private fun act(block: suspend () -> Unit) {
        if (flow.getAndUpdate { if (it.busy) it else it.copy(busy = true, problem = null) }.busy) return
        scope.launch {
            try {
                block()
            } finally {
                flow.update { it.copy(busy = false) }
            }
        }
    }
}

private fun AuthError.toProblem(): InviteScreenProblem =
    when (this) {
        is AuthError.Api -> {
            when (error) {
                ApiError.INVITE_LIMIT -> InviteScreenProblem.OPEN_LIMIT
                ApiError.RATE_LIMITED -> InviteScreenProblem.RATE_LIMITED
                ApiError.NETWORK -> InviteScreenProblem.OFFLINE
                ApiError.UNAUTHORIZED -> InviteScreenProblem.NOT_SIGNED_IN
                else -> InviteScreenProblem.FAILED
            }
        }

        is AuthError.Local -> {
            if (this == AuthError.Local.NOT_REGISTERED) InviteScreenProblem.NOT_SIGNED_IN else InviteScreenProblem.FAILED
        }
    }
