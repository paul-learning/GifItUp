package app.gifitup.shared.presentation.chat

import app.gifitup.shared.data.network.Session
import app.gifitup.shared.domain.auth.AuthResult
import app.gifitup.shared.domain.chat.ChatKind
import app.gifitup.shared.domain.chat.ChatPort
import app.gifitup.shared.domain.chat.ReadMarks
import app.gifitup.shared.domain.chat.chatKind
import app.gifitup.shared.domain.contacts.KeyBytes
import app.gifitup.shared.domain.groups.GroupInfo
import app.gifitup.shared.domain.groups.RejectReason
import app.gifitup.shared.domain.groups.SyncOutcome
import app.gifitup.shared.domain.groups.WelcomeOutcome
import app.gifitup.shared.domain.messages.ChatMessage
import app.gifitup.shared.domain.messages.HistoryAccess
import app.gifitup.shared.domain.messages.HistoryStoreException
import app.gifitup.shared.domain.messages.MessageBody
import app.gifitup.shared.domain.messages.ResetResult
import app.gifitup.shared.domain.session.SessionHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** One row of the chat list. [lastAt] is when this device saw the last message (seconds, its own clock), `null` if none. */
@Suppress("LongParameterList") // One property per field of a list row.
class ConversationSummary(
    val info: GroupInfo,
    val title: ConversationTitle,
    val preview: Preview,
    val lastAt: Long?,
    val unread: Int,
    val problem: ConversationProblem?,
    /** The history of this conversation can't be read: [preview] and [unread] mean nothing, a marker is shown instead. */
    val historyProblem: Boolean = false,
    /** Records of this chat that could not be read: with one other member they make the kind unknown (the name may be in one). */
    val unreadable: Int = 0,
) {
    val id: KeyBytes get() = info.id

    val kind: ChatKind get() = chatKind(info.others.size, title is ConversationTitle.Named, unreadable)
}

/** What the list shows (#217): everything, only chats with one other person, or only groups. */
enum class ChatFilter {
    ALL,
    DIRECT,
    GROUPS,
    ;

    /**
     * By [ConversationSummary.kind]: a chat of unknown kind (members not known, or a record that can't be read) is only in [ALL].
     */
    fun matches(conversation: ConversationSummary): Boolean =
        when (this) {
            ALL -> true
            DIRECT -> conversation.kind == ChatKind.DIRECT
            GROUPS -> conversation.kind == ChatKind.GROUP
        }
}

data class ChatListState(
    val loaded: Boolean = false,
    val refreshing: Boolean = false,
    val conversations: List<ConversationSummary> = emptyList(),
    val problem: ChatProblem? = null,
    /** "Reset history" is offered: the history key is missing or broken, and only a reset helps (behind a confirmation). */
    val canResetHistory: Boolean = false,
    /** The last "reset history" did not finish: the history is as it was and the reset can be tried again. */
    val resetFailed: Boolean = false,
    val filter: ChatFilter = ChatFilter.ALL,
    /** The last accept, decline or leave did not finish: nothing was deleted, and it can be tried again (#279). */
    val actionFailed: Boolean = false,
    /** Welcomes left waiting because the device holds 50 chats (or one of them is stopped): deleting a chat lets them join (#279). */
    val welcomesWaiting: Int = 0,
    /** Requests dropped by the last welcome check because 10 were already waiting: the senders can add again later (#279). */
    val requestsDropped: Int = 0,
) {
    /** [conversations] the [filter] lets through, without the requests, in the same order. */
    val visible: List<ConversationSummary> get() = conversations.filter { it.info.requestedBy == null && filter.matches(it) }

    /** The chats non-friends started (#279): shown apart from the filter, with the sender's name and nothing else. */
    val requests: List<ConversationSummary> get() = conversations.filter { it.info.requestedBy != null }
}

/**
 * The chat list's logic (#25): what is on this device, newest activity first, with a refresh that joins new groups and
 * reads every group's log. The list is built from the group engine's own state and the message store, so it shows the
 * history of this process even while the network is down (the history is in memory until #177).
 * - The list from this device is shown first, before the network steps of a refresh (#27).
 * - One refresh at a time; a refresh that fails shows what is known and names the problem.
 * - A group that is stopped, read-only or gone is listed with its problem and not synced.
 * - [leave] drops the state (the screen is gone); an answer for an older visit is dropped.
 */
@Suppress("TooManyFunctions") // The list's actions, the refresh and the hint refresh (#27) share one single-flight state.
class ChatListStateHolder(
    private val scope: CoroutineScope,
    private val sessions: SessionHolder,
    private val chat: ChatPort,
    private val store: HistoryAccess,
    private val marks: ReadMarks,
) {
    private val flow = MutableStateFlow(ChatListState())
    val state: StateFlow<ChatListState> = flow.asStateFlow()
    private var visit = 0

    // What the last welcome check left waiting or dropped (#279); shown by the next list.
    private var welcomesWaiting = 0
    private var requestsDropped = 0

    /** The list is on screen: set by the screen's first [refresh], cleared by [leave]. */
    private var visible = false
    private var running = false

    /** A retry came while a refresh was running: one more refresh follows it, so the tap is never dropped. */
    private var refreshAgain = false
    private var resetting = false

    /** Kept over [leave]: the list is left and opened again when the user goes to another screen and back. */
    private var filter = ChatFilter.ALL

    fun showOnly(choice: ChatFilter) {
        filter = choice
        flow.value = flow.value.copy(filter = choice)
    }

    /** The user's "try again" for a history that could not be opened: opens it again and rebuilds the list. */
    fun retryHistory() {
        scope.launch {
            store.retry()
            if (running) refreshAgain = true else refresh()
        }
    }

    /**
     * The user's confirmed "reset history": the stored history of this device is deleted and the list is rebuilt. The
     * confirmation is the screen's; a reset that does not finish names the problem and can be tried again.
     */
    fun resetHistory() {
        // One reset at a time, and the offer is gone at once: a second tap must not delete what was saved since the first.
        if (resetting) return
        resetting = true
        flow.value = flow.value.copy(canResetHistory = false, resetFailed = false)
        scope.launch {
            try {
                if (store.reset() == ResetResult.FAILED) {
                    flow.value = flow.value.copy(resetFailed = true, canResetHistory = store.canReset())
                } else if (running) {
                    refreshAgain = true
                } else {
                    refresh()
                }
            } finally {
                resetting = false
            }
        }
    }

    /** What one refresh does: [groups] `null` is every group; [welcomes] joins the groups the user was added to first. */
    private class Request(
        val groups: Set<KeyBytes>?,
        val welcomes: Boolean,
    ) {
        fun merge(other: Request) =
            Request(
                if (groups == null || other.groups == null) null else groups + other.groups,
                welcomes || other.welcomes,
            )
    }

    /** A push hint came while a refresh was running (#27): what it asked for follows, merged, so the hint is never dropped. */
    private var queued: Request? = null

    /**
     * The refresh a push hint asks for (#27): reads only the log of [groups] (and joins new groups if [welcomes]) and
     * rebuilds the list from what is stored. An empty set reads nothing and only rebuilds the list, which is what follows
     * the open conversation's own read. A hint names no more than the one group it is about, so this costs one request per
     * group, not one per chat.
     */
    fun refreshOnly(
        groups: Set<KeyBytes>,
        welcomes: Boolean = false,
    ) {
        // A hint refreshes the list only while the list is on screen, like the timer: a state nobody shows is not built.
        if (!visible) return
        val request = Request(groups, welcomes)
        if (running) queued = queued?.merge(request) ?: request else start(request)
    }

    /** The user accepts a request (#279): from now on it is a normal chat. */
    fun acceptRequest(id: KeyBytes) = act { chat.acceptRequest(it, id) }

    /** The user declines a request or leaves a chat (#279): left on the server, keys and stored messages deleted. */
    fun leaveChat(id: KeyBytes) = act { chat.leave(it, id) }

    private fun act(call: suspend (Session) -> AuthResult<SyncOutcome>) {
        flow.value = flow.value.copy(actionFailed = false)
        scope.launch {
            val done = sessions.withSessionResult(call)
            if (done is AuthResult.Ok && done.value is SyncOutcome.CaughtUp) {
                refreshOnly(emptySet())
            } else {
                flow.value = flow.value.copy(actionFailed = true)
            }
        }
    }

    /** Joins new groups, reads every active group's log, and rebuilds the list. */
    fun refresh() {
        visible = true
        if (running) return
        start(Request(null, true))
    }

    private fun start(request: Request) {
        running = true
        val forVisit = visit
        flow.value = flow.value.copy(refreshing = true)
        scope.launch {
            try {
                run(forVisit, request)
            } finally {
                // After leave() a newer refresh may be running: this one no longer owns the flag.
                if (forVisit == visit) {
                    running = false
                    flow.value = flow.value.copy(refreshing = false)
                    val next = queued
                    queued = null
                    if (refreshAgain) {
                        refreshAgain = false
                        refresh()
                    } else if (next != null) {
                        start(next)
                    }
                }
            }
        }
    }

    fun leave() {
        visible = false
        visit++
        running = false
        refreshAgain = false
        queued = null
        flow.value = ChatListState(filter = filter)
    }

    private suspend fun run(
        forVisit: Int,
        request: Request,
    ) {
        var problem: ChatProblem? = null
        // A history that can't be used is not fed: every message would fail and stall, once per group and tick (ADR-0017).
        val usable = store.usable()
        // The groups and the history are on this device: show them before any network step, so the list is there at once
        // and the refresh below only updates it (#27). The previous problem stays on screen until the later publish, which
        // carries this refresh's own (#261 CR-2).
        (sessions.withSessionResult { chat.groups(it) } as? AuthResult.Ok)?.let { publish(forVisit, it.value.groups, flow.value.problem) }
        if (usable && request.welcomes) problem = welcomeStep()
        when (val groups = sessions.withSessionResult { chat.groups(it) }) {
            is AuthResult.Failed -> {
                // What is known stays on screen; only the problem is new.
                problem = problem ?: groups.error.toChatProblem()
                if (forVisit == visit) flow.value = flow.value.copy(loaded = true, problem = problem, canResetHistory = store.canReset())
            }

            is AuthResult.Ok -> {
                if (usable) {
                    for (info in groups.value.groups.filter { it.problem() == null && request.groups?.contains(it.id) != false }) {
                        problem = problem ?: syncProblem(info)
                    }
                }
                publish(forVisit, groups.value.groups, problem)
            }
        }
    }

    /** Joins the groups the user was added to; remembers what was left waiting or dropped (#279); the problem, if any. */
    private suspend fun welcomeStep(): ChatProblem? =
        when (val joined = sessions.withSessionResult { chat.welcomes(it) }) {
            is AuthResult.Failed -> {
                joined.error.toChatProblem()
            }

            // A welcome step that stopped part-way (friends list unreadable, the server slowing us down, ...) used to be
            // dropped here, and new chats silently never opened (#27).
            is AuthResult.Ok -> {
                val results = joined.value.results
                welcomesWaiting = results.count { it is WelcomeOutcome.Skipped }
                requestsDropped = results.count { it is WelcomeOutcome.Rejected && it.reason == RejectReason.REQUEST_LIMIT }
                joined.value.stoppedBy?.toListProblem()
            }
        }

    private suspend fun syncProblem(info: GroupInfo): ChatProblem? =
        when (val synced = sessions.withSessionResult { chat.sync(it, info.id) }) {
            is AuthResult.Failed -> synced.error.toChatProblem()
            is AuthResult.Ok -> synced.value.toListProblem()
        }

    private suspend fun publish(
        forVisit: Int,
        groups: List<GroupInfo>,
        problem: ChatProblem?,
    ) {
        val rows = groups.map { summary(it) }.sortedWith(compareByDescending<ConversationSummary> { it.lastAt ?: Long.MIN_VALUE })
        // A broken history is the one problem the user can act on ("Try again"), so it wins over a sync problem.
        val shown = if (rows.any { it.historyProblem }) ChatProblem.HISTORY else problem
        val resettable = shown == ChatProblem.HISTORY && store.canReset()
        if (forVisit ==
            visit
        ) {
            flow.value =
                flow.value.copy(
                    loaded = true,
                    conversations = rows,
                    problem = shown,
                    canResetHistory = resettable,
                    welcomesWaiting = welcomesWaiting,
                    requestsDropped = requestsDropped,
                )
        }
    }

    private suspend fun summary(info: GroupInfo): ConversationSummary {
        val messages: List<ChatMessage>
        val unread: Int
        val unreadable: Int
        try {
            messages = store.messages(info.id)
            unread = marks.unread(info.id, messages)
            unreadable = store.unreadable(info.id)
        } catch (_: HistoryStoreException) {
            // Never an empty history shown as normal: the row says so, and "unread 0" would hide new messages. Also a history
            // that closed between the two reads (a wipe) or a read mark that can't be read.
            return ConversationSummary(
                info,
                titleOf(info, emptyList()),
                Preview.Nothing,
                null,
                0,
                info.problem(),
                historyProblem = true,
                unreadable = 1,
            )
        }
        // A membership notice is not a message: the row's line and time are those of the last real one.
        val last = messages.lastOrNull { it.body !is MessageBody.Membership }
        return ConversationSummary(
            info = info,
            title = titleOf(info, messages),
            preview = previewOf(last),
            lastAt = last?.at,
            unread = unread,
            problem = info.problem(),
            unreadable = unreadable,
        )
    }
}
