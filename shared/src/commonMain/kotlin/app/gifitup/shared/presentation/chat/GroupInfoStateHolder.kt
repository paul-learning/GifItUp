package app.gifitup.shared.presentation.chat

import app.gifitup.shared.domain.auth.AuthResult
import app.gifitup.shared.domain.chat.AddPolicy
import app.gifitup.shared.domain.chat.ChatPort
import app.gifitup.shared.domain.chat.FriendNames
import app.gifitup.shared.domain.chat.settledName
import app.gifitup.shared.domain.groups.GroupInfo
import app.gifitup.shared.domain.groups.SyncOutcome
import app.gifitup.shared.domain.messages.HistoryAccess
import app.gifitup.shared.domain.messages.HistoryStoreException
import app.gifitup.shared.domain.session.SessionHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class GroupInfoProblem {
    /** The friends list can't be read (the contact book is missing or damaged). */
    FRIENDS_UNAVAILABLE,

    /** Nobody was chosen. */
    NOBODY,

    /** Some of the chosen friends are not in the group yet ([GroupInfoState.notAdded]); they can be chosen again. */
    PARTLY,

    /** The friends are in, but the group's name is not accepted by the server yet: they may see no name. */
    NAME_NOT_SENT,

    OFFLINE,
    NOT_SIGNED_IN,

    /** The server asked the app to slow down (a rate limit); nothing is lost. */
    THROTTLED,
    FAILED,

    /** Leaving did not finish: nothing was deleted, and it can be tried again. */
    LEAVE_FAILED,
}

data class GroupInfoState(
    /** The group's name, `null` while it has none (or this device can't read it). */
    val name: String? = null,
    /** The usernames of the other members, with the ones added from this screen. */
    val members: List<String> = emptyList(),
    val loaded: Boolean = false,
    /** Adding is offered: the chat is a group this device can still grow (a client policy, [AddPolicy]). */
    val canAdd: Boolean = false,
    /** The friends who are not in the group yet. */
    val candidates: List<String> = emptyList(),
    val selected: Set<String> = emptySet(),
    val adding: Boolean = false,
    val notAdded: List<String> = emptyList(),
    val problem: GroupInfoProblem? = null,
    /** How each member stands with the saved friends (by key, #31); empty until the screen has read the contact book. */
    val standing: Map<String, MemberStanding> = emptyMap(),
    /** The contact book can't be read: no member is marked, and the screen says so instead of showing nothing (#240). */
    val standingUnknown: Boolean = false,
    /** The user left the chat (#279): it is gone from this device and the screen goes back to the list. */
    val left: Boolean = false,
) {
    val canSubmit: Boolean get() = canAdd && !adding && selected.isNotEmpty()
}

/**
 * A group's info screen (#217 part 4b): the name and the members, and adding friends who are not in it. Only a **group**
 * grows ([AddPolicy]; [ChatPort.add] refuses anything else too). Each friend is added with its own commit (ADR-0016); the
 * name is sent again after the adds, because a member only reads what was sent after they joined. A friend who can't be
 * added doesn't undo the others: it is listed in [GroupInfoState.notAdded].
 */
@Suppress("TooManyFunctions") // One small function per user action of the screen (open, toggle, add, rename again, leave).
class GroupInfoStateHolder(
    private val scope: CoroutineScope,
    private val sessions: SessionHolder,
    private val chat: ChatPort,
    private val friends: FriendNames,
    private val store: HistoryAccess,
    private val group: GroupInfo,
) {
    private val flow = MutableStateFlow(GroupInfoState(members = group.others))
    val state: StateFlow<GroupInfoState> = flow.asStateFlow()
    private var visit = 0
    private var running = false

    /** The group's other leaves with their keys: from the list that opened the screen, read again after an add. */
    private var leaves = group.leaves

    /** Shows what this device knows, then reads the server's member list into the contact book and shows the result. */
    fun open() =
        scope.launch {
            val forVisit = visit
            show(forVisit, null)
            val refreshed = sessions.withSessionResult { friends.refresh(it) }
            show(forVisit, (refreshed as? AuthResult.Failed)?.error?.toChatProblem()?.toInfoProblem())
        }

    private suspend fun show(
        forVisit: Int,
        problem: GroupInfoProblem?,
    ) {
        val names = friends.names()
        val name = nameOf()
        val allowed = AddPolicy(store).allows(group)
        val pinned = friends.pinned()
        if (forVisit != visit) return
        val current = flow.value
        flow.value =
            current.copy(
                name = name,
                loaded = true,
                canAdd = allowed,
                standing = pinned?.let { standingsOf(current.members, leaves, it) }.orEmpty(),
                standingUnknown = pinned == null,
                candidates = names.orEmpty().filter { it !in current.members }.sorted(),
                selected = current.selected.filter { it in names.orEmpty() && it !in current.members }.toSet(),
                problem = problem ?: GroupInfoProblem.FRIENDS_UNAVAILABLE.takeIf { names == null } ?: current.problem,
            )
    }

    private suspend fun nameOf(): String? =
        try {
            settledName(store.messages(group.id))
        } catch (_: HistoryStoreException) {
            null
        }

    /** The user's confirmed "leave this chat" (#279): left on the server, keys and stored messages deleted. */
    fun leaveChat() {
        if (running || flow.value.adding || flow.value.left) return
        running = true
        scope.launch {
            try {
                val done = sessions.withSessionResult { chat.leave(it, group.id) }
                flow.value =
                    if (done is AuthResult.Ok && done.value is SyncOutcome.CaughtUp) {
                        flow.value.copy(left = true, problem = null)
                    } else {
                        flow.value.copy(problem = GroupInfoProblem.LEAVE_FAILED)
                    }
            } finally {
                running = false
            }
        }
    }

    fun toggle(name: String) {
        val current = flow.value
        if (name !in current.candidates || current.adding) return
        val next = if (name in current.selected) current.selected - name else current.selected + name
        if (next.size <= NewChatStateHolder.MAX_FRIENDS) flow.value = current.copy(selected = next, problem = null)
    }

    /** Adds the chosen friends one by one, then sends the name again. */
    fun add() {
        val current = flow.value
        if (running || !current.canAdd || current.adding) return
        if (current.selected.isEmpty()) {
            flow.value = current.copy(problem = GroupInfoProblem.NOBODY)
            return
        }
        running = true
        flow.value = current.copy(adding = true, problem = null, notAdded = emptyList())
        scope.launch {
            try {
                addAll(current.selected.sorted())
            } finally {
                running = false
            }
        }
    }

    /** "Send the name again" after [GroupInfoProblem.NAME_NOT_SENT]. */
    fun retryName() {
        val current = flow.value
        val name = current.name
        val retryable = current.problem == GroupInfoProblem.NAME_NOT_SENT && !current.adding
        if (running || !retryable || name == null) return
        running = true
        flow.value = current.copy(adding = true, problem = null)
        scope.launch {
            try {
                val sent = sendName(name)
                flow.value = flow.value.copy(adding = false, problem = if (sent) null else GroupInfoProblem.NAME_NOT_SENT)
            } finally {
                running = false
            }
        }
    }

    private suspend fun addAll(names: List<String>) {
        val added = mutableListOf<String>()
        val missed = mutableListOf<String>()
        var failure: GroupInfoProblem? = null
        for (name in names) {
            when (val result = sessions.withSessionResult { chat.add(it, group.id, name) }) {
                is AuthResult.Ok -> {
                    if (result.value is SyncOutcome.Sent) added += name else missed += name
                }

                is AuthResult.Failed -> {
                    missed += name
                    failure = failure ?: result.error.toChatProblem().toInfoProblem()
                }
            }
        }
        // The name goes last, once everyone who could be added is in; only if somebody was added, and the group has a name.
        val name = flow.value.name
        val nameSent = added.isEmpty() || name == null || sendName(name)
        val now = if (added.isEmpty()) null else rereadMembers()
        val current = flow.value
        val members = now ?: (current.members + added)
        val standing = friends.pinned()?.let { standingsOf(members, leaves, it) }.orEmpty()
        flow.value =
            current.copy(
                members = members,
                standing = standing,
                candidates = current.candidates.filter { it !in added },
                selected = current.selected - added.toSet(),
                adding = false,
                notAdded = missed,
                problem = problemOf(failure, missed, nameSent),
            )
    }

    /** The members and leaves as the engine holds them after an add; `null` if they can't be read (the names added are used). */
    private suspend fun rereadMembers(): List<String>? {
        val info =
            (sessions.withSessionResult { chat.groups(it) } as? AuthResult.Ok)?.value?.groups?.firstOrNull { it.id == group.id }
                ?: return null
        leaves = info.leaves
        return info.others
    }

    private suspend fun sendName(name: String): Boolean {
        val named = sessions.withSessionResult { chat.rename(it, group.id, name) }
        return named is AuthResult.Ok && named.value is SyncOutcome.Sent
    }

    /** The screen closed: the state is reset, unless an add is still running (it finishes; the next open makes a new holder). */
    fun leave() {
        if (running) return
        visit++
        flow.value = GroupInfoState(members = group.others)
    }
}

private fun ChatProblem.toInfoProblem(): GroupInfoProblem =
    when (this) {
        ChatProblem.OFFLINE -> GroupInfoProblem.OFFLINE
        ChatProblem.NOT_SIGNED_IN -> GroupInfoProblem.NOT_SIGNED_IN
        ChatProblem.THROTTLED -> GroupInfoProblem.THROTTLED
        ChatProblem.FAILED, ChatProblem.HISTORY -> GroupInfoProblem.FAILED
    }

private fun problemOf(
    failure: GroupInfoProblem?,
    missed: List<String>,
    nameSent: Boolean,
): GroupInfoProblem? =
    when {
        failure != null -> failure
        missed.isNotEmpty() -> GroupInfoProblem.PARTLY
        !nameSent -> GroupInfoProblem.NAME_NOT_SENT
        else -> null
    }
