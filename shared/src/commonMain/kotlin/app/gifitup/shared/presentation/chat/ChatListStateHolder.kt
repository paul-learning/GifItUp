package app.gifitup.shared.presentation.chat

import app.gifitup.shared.domain.auth.AuthResult
import app.gifitup.shared.domain.chat.ChatPort
import app.gifitup.shared.domain.chat.ReadMarks
import app.gifitup.shared.domain.contacts.KeyBytes
import app.gifitup.shared.domain.groups.GroupInfo
import app.gifitup.shared.domain.groups.SyncOutcome
import app.gifitup.shared.domain.messages.ChatMessage
import app.gifitup.shared.domain.messages.HistoryAccess
import app.gifitup.shared.domain.messages.HistoryStoreException
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
) {
    val id: KeyBytes get() = info.id
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
)

/**
 * The chat list's logic (#25): what is on this device, newest activity first, with a refresh that joins new groups and
 * reads every group's log. The list is built from the group engine's own state and the message store, so it shows the
 * history of this process even while the network is down (the history is in memory until #177).
 * - One refresh at a time; a refresh that fails shows what is known and names the problem.
 * - A group that is stopped, read-only or gone is listed with its problem and not synced.
 * - [leave] drops the state (the screen is gone); an answer for an older visit is dropped.
 */
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
    private var running = false

    /** A retry came while a refresh was running: one more refresh follows it, so the tap is never dropped. */
    private var refreshAgain = false
    private var resetting = false

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

    /** Joins new groups, reads every active group's log, and rebuilds the list. */
    fun refresh() {
        if (running) return
        running = true
        val forVisit = visit
        flow.value = flow.value.copy(refreshing = true)
        scope.launch {
            try {
                run(forVisit)
            } finally {
                // After leave() a newer refresh may be running: this one no longer owns the flag.
                if (forVisit == visit) {
                    running = false
                    flow.value = flow.value.copy(refreshing = false)
                    if (refreshAgain) {
                        refreshAgain = false
                        refresh()
                    }
                }
            }
        }
    }

    fun leave() {
        visit++
        running = false
        refreshAgain = false
        flow.value = ChatListState()
    }

    private suspend fun run(forVisit: Int) {
        var problem: ChatProblem? = null
        // A history that can't be used is not fed: every message would fail and stall, once per group and tick (ADR-0017).
        val usable = store.usable()
        if (usable) {
            val joined = sessions.withSessionResult { chat.welcomes(it) }
            if (joined is AuthResult.Failed) problem = joined.error.toChatProblem()
        }
        when (val groups = sessions.withSessionResult { chat.groups(it) }) {
            is AuthResult.Failed -> {
                // What is known stays on screen; only the problem is new.
                problem = problem ?: groups.error.toChatProblem()
                if (forVisit == visit) flow.value = flow.value.copy(loaded = true, problem = problem, canResetHistory = store.canReset())
            }

            is AuthResult.Ok -> {
                if (usable) {
                    for (info in groups.value.groups.filter { it.status.toProblem() == null }) {
                        problem = problem ?: syncProblem(info)
                    }
                }
                publish(forVisit, groups.value.groups, problem)
            }
        }
    }

    private suspend fun syncProblem(info: GroupInfo): ChatProblem? =
        when (val synced = sessions.withSessionResult { chat.sync(it, info.id) }) {
            is AuthResult.Failed -> synced.error.toChatProblem()
            is AuthResult.Ok -> if (synced.value is SyncOutcome.CaughtUp) null else ChatProblem.FAILED
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
            flow.value = flow.value.copy(loaded = true, conversations = rows, problem = shown, canResetHistory = resettable)
        }
    }

    private suspend fun summary(info: GroupInfo): ConversationSummary {
        val messages: List<ChatMessage>
        val unread: Int
        try {
            messages = store.messages(info.id)
            unread = marks.unread(info.id, messages)
        } catch (_: HistoryStoreException) {
            // Never an empty history shown as normal: the row says so, and "unread 0" would hide new messages. Also a history
            // that closed between the two reads (a wipe) or a read mark that can't be read.
            return ConversationSummary(
                info,
                titleOf(info, emptyList()),
                Preview.Nothing,
                null,
                0,
                info.status.toProblem(),
                historyProblem = true,
            )
        }
        val last = messages.lastOrNull()
        return ConversationSummary(
            info = info,
            title = titleOf(info, messages),
            preview = previewOf(last),
            lastAt = last?.at,
            unread = unread,
            problem = info.status.toProblem(),
        )
    }
}
