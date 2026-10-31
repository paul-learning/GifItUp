package app.gifitup.shared.presentation.chat

import app.gifitup.shared.data.content.GifReference
import app.gifitup.shared.domain.auth.AuthResult
import app.gifitup.shared.domain.chat.ChatPort
import app.gifitup.shared.domain.chat.ReadMarks
import app.gifitup.shared.domain.contacts.KeyBytes
import app.gifitup.shared.domain.groups.GroupInfo
import app.gifitup.shared.domain.groups.SyncOutcome
import app.gifitup.shared.domain.messages.CaptionInput
import app.gifitup.shared.domain.messages.CaptionVerdict
import app.gifitup.shared.domain.messages.ChatMessage
import app.gifitup.shared.domain.messages.DeliveryState
import app.gifitup.shared.domain.messages.HistoryAccess
import app.gifitup.shared.domain.messages.HistoryStoreException
import app.gifitup.shared.domain.messages.MessageBody
import app.gifitup.shared.domain.session.SessionHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** One message of the conversation. [sender] is the MLS-authenticated username, `null` for one of ours. */
class MessageView(
    val reference: KeyBytes,
    val sender: String?,
    val body: MessageBody,
    val state: DeliveryState?,
    val at: Long,
)

/** Why the last send did not go as asked. */
enum class SendProblem {
    /** The caption breaks the text rules or is too long: nothing was sent. */
    CAPTION,

    /** An earlier message is still unsettled and did not go through: it is tried first, the new GIF was not sent. */
    EARLIER_FIRST,

    /** The previous send has not finished; this one was not made (the button is off while [ConversationState.sending]). */
    IN_PROGRESS,

    /** Messages or a commit are waiting: read the conversation first, then send again. */
    SYNC_FIRST,

    /** The conversation is stopped, read-only or gone. */
    STOPPED,

    /** No network or not signed in: nothing was sent. */
    OFFLINE,

    /** The server refused the message; it is shown as failed. */
    REFUSED,

    /** The history on this device is not available (or the device's storage failed): nothing was sent or shown from memory. */
    HISTORY,

    FAILED,
}

data class ConversationState(
    val title: ConversationTitle,
    /** The usernames of the other members (empty for a group this device is out of). */
    val members: List<String> = emptyList(),
    val loaded: Boolean = false,
    val refreshing: Boolean = false,
    val messages: List<MessageView> = emptyList(),
    val problem: ChatProblem? = null,
    val conversationProblem: ConversationProblem? = null,
    val sendProblem: SendProblem? = null,
    val sending: Boolean = false,
    /** How many stored records of this conversation could not be read (kept, never shown). */
    val unreadable: Int = 0,
) {
    /** A message of ours was stored but not answered: [ConversationStateHolder.resend] posts it again. */
    val hasUnsettled: Boolean get() = messages.any { it.state == DeliveryState.SENDING }
    val canSend: Boolean get() = conversationProblem == null && problem != ChatProblem.HISTORY && !sending
}

/**
 * One conversation's logic (#25): the messages in the order they arrived, reading new ones, and sending a GIF. The
 * conversation is marked read whenever it is shown. Sending is one at a time; the picker (#23) hands over a validated
 * [GifReference], the caption is checked by the codec and a bad one sends nothing. An answer for an older visit is
 * dropped after [leave].
 */
@Suppress("LongParameterList", "TooManyFunctions") // Constructor injection of the collaborators; one small function per user action.
class ConversationStateHolder(
    private val scope: CoroutineScope,
    private val sessions: SessionHolder,
    private val chat: ChatPort,
    private val store: HistoryAccess,
    private val marks: ReadMarks,
    private val group: GroupInfo,
    /** The caption field's rules: the live counter and whether the text may be sent; [send] applies the same check. */
    val captions: CaptionInput,
) {
    /** Which conversation this is. */
    val id: KeyBytes get() = group.id

    private val flow =
        MutableStateFlow(
            ConversationState(ConversationTitle.Members(group.others), group.others, conversationProblem = group.status.toProblem()),
        )
    val state: StateFlow<ConversationState> = flow.asStateFlow()
    private var visit = 0
    private var running = false
    private var sendRunning = false

    /**
     * Shows what is stored at once, settles what a crash left unsettled, then reads the log. The unsettled message is
     * posted again (`resume`) only when the conversation is usable and the history is readable (ADR-0017).
     */
    fun open() = act { forVisit -> resumeUnsettled(forVisit) }

    /** The user's "try again" for a history that could not be opened: opens it again and shows the conversation. */
    fun retryHistory() {
        scope.launch {
            store.retry()
            refresh()
        }
    }

    fun refresh() = act { }

    /** Sends [reference] with an optional [caption]; a caption that fails [captions] sends nothing. */
    fun send(
        reference: GifReference,
        caption: String?,
    ) {
        val checked = captions.check(caption.orEmpty())
        if (checked.verdict != CaptionVerdict.OK) {
            flow.value = flow.value.copy(sendProblem = SendProblem.CAPTION)
            return
        }
        sendWith { sessions.withSessionResult { chat.sendGif(it, group.id, reference, checked.caption) } }
    }

    /** Posts the unsettled message again. */
    fun resend() = sendWith { sessions.withSessionResult { chat.resume(it, group.id) } }

    /**
     * A send has its own gate, so reading the log never swallows it. It is refused with a reason, never dropped
     * silently, if the conversation is stopped or the previous send is still going.
     */
    private fun sendWith(call: suspend () -> AuthResult<SyncOutcome>) {
        val blocked =
            when {
                flow.value.conversationProblem != null -> SendProblem.STOPPED
                flow.value.problem == ChatProblem.HISTORY -> SendProblem.HISTORY
                sendRunning -> SendProblem.IN_PROGRESS
                else -> null
            }
        if (blocked != null) {
            flow.value = flow.value.copy(sendProblem = blocked)
            return
        }
        sendRunning = true
        val forVisit = visit
        flow.value = flow.value.copy(sending = true, sendProblem = null)
        scope.launch {
            try {
                problemOf(call())?.let { problem -> change(forVisit) { it.copy(sendProblem = problem) } }
            } finally {
                if (forVisit == visit) {
                    sendRunning = false
                    show(forVisit)
                    change(forVisit) { it.copy(sending = false) }
                }
            }
        }
    }

    fun leave() {
        visit++
        running = false
        sendRunning = false
        flow.value =
            ConversationState(ConversationTitle.Members(group.others), group.others, conversationProblem = group.status.toProblem())
    }

    private fun act(block: suspend (Int) -> Unit) {
        if (running) return
        running = true
        val forVisit = visit
        scope.launch {
            try {
                show(forVisit)
                block(forVisit)
                // A broken history is not fed: receiving into it would only fail and stall the log (ADR-0017).
                if (flow.value.conversationProblem == null && flow.value.problem != ChatProblem.HISTORY) readLog(forVisit)
            } finally {
                if (forVisit == visit) {
                    running = false
                    show(forVisit)
                    change(forVisit) { it.copy(refreshing = false, loaded = true) }
                }
            }
        }
    }

    private suspend fun readLog(forVisit: Int) {
        change(forVisit) { it.copy(refreshing = true) }
        when (val synced = sessions.withSessionResult { chat.sync(it, group.id) }) {
            is AuthResult.Failed -> {
                change(forVisit) { it.copy(problem = synced.error.toChatProblem()) }
            }

            is AuthResult.Ok -> {
                change(
                    forVisit,
                ) { it.copy(problem = if (synced.value is SyncOutcome.CaughtUp) null else ChatProblem.FAILED) }
            }
        }
    }

    private suspend fun resumeUnsettled(forVisit: Int) {
        val state = flow.value
        if (!state.hasUnsettled || state.conversationProblem != null || state.problem == ChatProblem.HISTORY) return
        sessions.withSessionResult { chat.resume(it, group.id) }
        show(forVisit)
    }

    /** Rebuilds the message list from the store and marks the conversation read. */
    private suspend fun show(forVisit: Int) {
        val messages =
            try {
                store.messages(group.id)
            } catch (_: HistoryStoreException) {
                // Never an empty conversation shown as normal: the problem is named and nothing can be sent.
                change(forVisit) { it.copy(problem = ChatProblem.HISTORY, messages = emptyList(), unreadable = 0) }
                return
            }
        val unreadable =
            try {
                store.unreadable(group.id)
            } catch (_: HistoryStoreException) {
                0
            }
        // Nothing is marked read for a screen that is gone.
        if (forVisit != visit) return
        marks.markRead(group.id, messages)
        change(forVisit) {
            val cleared = if (it.problem == ChatProblem.HISTORY) null else it.problem
            it.copy(
                title = titleOf(group, messages),
                messages = messages.map(ChatMessage::toView),
                problem = cleared,
                unreadable = unreadable,
            )
        }
    }

    private fun change(
        forVisit: Int,
        update: (ConversationState) -> ConversationState,
    ) {
        if (forVisit == visit) flow.value = update(flow.value)
    }
}

private fun ChatMessage.toView() = MessageView(reference, sender, body, state, at)

private fun problemOf(result: AuthResult<SyncOutcome>): SendProblem? =
    when (result) {
        is AuthResult.Failed -> if (result.error.toChatProblem() == ChatProblem.FAILED) SendProblem.FAILED else SendProblem.OFFLINE
        is AuthResult.Ok -> outcomeProblem(result.value)
    }

private fun outcomeProblem(outcome: SyncOutcome): SendProblem? =
    when (outcome) {
        is SyncOutcome.Sent, is SyncOutcome.CaughtUp, is SyncOutcome.InFlight -> null
        SyncOutcome.InvalidRequest -> SendProblem.CAPTION
        is SyncOutcome.PendingFirst -> SendProblem.EARLIER_FIRST
        SyncOutcome.Busy, SyncOutcome.CommitPending, is SyncOutcome.Retry, SyncOutcome.RekeyNeeded -> SendProblem.SYNC_FIRST
        is SyncOutcome.Stopped, SyncOutcome.NotJoined -> SendProblem.STOPPED
        is SyncOutcome.Unavailable -> SendProblem.OFFLINE
        SyncOutcome.StorageFailed -> SendProblem.HISTORY
        is SyncOutcome.Rejected -> SendProblem.REFUSED
        else -> SendProblem.FAILED
    }
