package app.gifitup.shared.presentation.chat

import app.gifitup.shared.data.content.ContentCodec
import app.gifitup.shared.domain.auth.AuthResult
import app.gifitup.shared.domain.chat.ChatPort
import app.gifitup.shared.domain.chat.FriendNames
import app.gifitup.shared.domain.contacts.KeyBytes
import app.gifitup.shared.domain.groups.GroupCreation
import app.gifitup.shared.domain.groups.SyncOutcome
import app.gifitup.shared.domain.session.SessionHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class NewChatProblem {
    /** The friends list can't be read (the contact book is missing or damaged). */
    FRIENDS_UNAVAILABLE,

    /** Nobody was chosen. */
    NOBODY,

    /** The first friend can't be reached: no KeyPackage is left for them (they have to open the app), or the key doesn't match. */
    CANT_REACH,

    /** The group exists on this device but its first message is not confirmed; the conversation posts it again. */
    UNFINISHED,

    /**
     * The group was made; the friends in [NewChatState.notAdded] are not in it yet (adding them later comes with the
     * conversation screen, #25 part 3).
     */
    PARTLY,

    /** The group was made, but its name is not accepted by the server yet: the chat still looks like a direct chat. */
    NAME_NOT_SENT,

    /** A group needs a name (1 to 64 characters, no line breaks). */
    NAME_INVALID,

    OFFLINE,
    NOT_SIGNED_IN,
    FAILED,
}

/** What the screen makes: a chat with one friend (direct), or a group with a name and one or more friends (#217). */
enum class NewChatMode { DIRECT, GROUP }

data class NewChatState(
    val mode: NewChatMode = NewChatMode.DIRECT,
    val name: String = "",
    val loaded: Boolean = false,
    val friends: List<String> = emptyList(),
    val selected: Set<String> = emptySet(),
    val creating: Boolean = false,
    /** The group that was made, `null` until then. */
    val created: KeyBytes? = null,
    val notAdded: List<String> = emptyList(),
    val problem: NewChatProblem? = null,
) {
    val canCreate: Boolean
        get() =
            !creating && created == null &&
                when (mode) {
                    NewChatMode.DIRECT -> selected.size == 1
                    NewChatMode.GROUP -> selected.isNotEmpty() && ContentCodec.encodeGroupMeta(name) != null
                }
}

/**
 * The new-chat flow (#25): pick friends, make a 1:1 chat (one friend) or a group (more). The first friend creates the
 * group, each further one is added with its own commit (ADR-0016). A friend that can't be added doesn't undo the group:
 * it is listed in [NewChatState.notAdded]. At most [MAX_FRIENDS] are chosen at once.
 */
@Suppress("TooManyFunctions") // One function per user action of the screen.
class NewChatStateHolder(
    private val scope: CoroutineScope,
    private val sessions: SessionHolder,
    private val chat: ChatPort,
    private val friends: FriendNames,
) {
    private val flow = MutableStateFlow(NewChatState())
    val state: StateFlow<NewChatState> = flow.asStateFlow()
    private var visit = 0
    private var running = false

    /** The last create ended with a problem and no group: its message survives a refresh of the friends list. */
    private var createFailed = false

    /** Shows the friends the contact book already has, then reads the server's member list and shows the result. */
    fun open() =
        scope.launch {
            val forVisit = visit
            show(forVisit, null)
            val refreshed = sessions.withSessionResult { friends.refresh(it) }
            show(forVisit, (refreshed as? AuthResult.Failed)?.error?.toNewChatProblem())
        }

    private suspend fun show(
        forVisit: Int,
        problem: NewChatProblem?,
    ) {
        val names = friends.names()
        if (forVisit != visit) return
        val current = flow.value
        flow.value =
            if (names == null) {
                // Only when nothing was made: a group that exists keeps its own result on screen.
                current.copy(loaded = true, problem = if (current.created == null) NewChatProblem.FRIENDS_UNAVAILABLE else current.problem)
            } else {
                current.copy(
                    loaded = true,
                    friends = names.sorted(),
                    selected = current.selected.filter { it in names }.toSet(),
                    problem =
                        problem ?: current.problem.takeIf { current.created != null || createFailed },
                )
            }
    }

    /** Switches between a chat with one friend and a new group; a direct chat keeps at most one friend chosen. */
    fun setMode(mode: NewChatMode) {
        val current = flow.value
        if (current.creating || current.created != null) return
        val kept = if (mode == NewChatMode.DIRECT) current.selected.take(1).toSet() else current.selected
        flow.value = current.copy(mode = mode, selected = kept, problem = null)
    }

    fun setName(text: String) {
        val current = flow.value
        if (current.creating || current.created != null) return
        flow.value = current.copy(name = text.take(MAX_NAME_INPUT), problem = null)
    }

    fun toggle(name: String) {
        val current = flow.value
        if (name !in current.friends || current.creating || current.created != null) return
        val next =
            when {
                current.mode == NewChatMode.DIRECT -> if (name in current.selected) emptySet() else setOf(name)
                name in current.selected -> current.selected - name
                else -> current.selected + name
            }
        if (next.size <= MAX_FRIENDS) {
            createFailed = false
            flow.value = current.copy(selected = next, problem = null)
        }
    }

    fun create() {
        val current = flow.value
        // A create that is still going (the screen was left and opened again) shows as `creating` and is never doubled.
        if (running) return
        if (!current.canCreate) {
            val problem =
                when {
                    current.selected.isEmpty() -> NewChatProblem.NOBODY
                    current.mode == NewChatMode.GROUP -> NewChatProblem.NAME_INVALID
                    else -> null
                }
            if (problem != null) flow.value = current.copy(problem = problem)
            return
        }
        running = true
        val forVisit = visit
        flow.value = current.copy(creating = true, problem = null)
        scope.launch {
            try {
                make(forVisit, current.selected.sorted(), current.name.takeIf { current.mode == NewChatMode.GROUP })
            } finally {
                running = false
                if (forVisit == visit) flow.value = flow.value.copy(creating = false)
            }
        }
    }

    /**
     * "Send the name again" after [NewChatProblem.NAME_NOT_SENT] (the server did not accept it, or no answer came): the same
     * name goes out once more, at the user's request. The group is kept either way.
     */
    fun retryName() {
        val current = flow.value
        val id = current.created
        if (running || id == null || current.problem != NewChatProblem.NAME_NOT_SENT) return
        running = true
        val forVisit = visit
        flow.value = current.copy(creating = true)
        scope.launch {
            try {
                val sent = sessions.withSessionResult { chat.rename(it, id, current.name) }
                val ok = sent is AuthResult.Ok && sent.value is SyncOutcome.Sent
                if (forVisit == visit) flow.value = flow.value.copy(problem = if (ok) null else NewChatProblem.NAME_NOT_SENT)
            } finally {
                running = false
                if (forVisit == visit) flow.value = flow.value.copy(creating = false)
            }
        }
    }

    /**
     * Drops the state, unless a create is still running: that one goes on, and its result stays in the state for when the
     * screen is opened again (the visit is kept, so the result is not dropped). The screen closes itself on a finished
     * result, so the next [leave] with nothing running starts the next visit clean.
     */
    fun leave() {
        if (running) return
        visit++
        createFailed = false
        flow.value = NewChatState()
    }

    private suspend fun make(
        forVisit: Int,
        names: List<String>,
        groupName: String?,
    ) {
        createFailed = false
        val creation = sessions.withSessionResult { chat.create(it, names.first()) }
        val result =
            when (creation) {
                is AuthResult.Failed -> {
                    NewChatState(
                        loaded = true,
                        friends = flow.value.friends,
                        selected = names.toSet(),
                        problem = creation.error.toNewChatProblem(),
                    )
                }

                is AuthResult.Ok -> {
                    afterCreation(creation.value, names, groupName)
                }
            }
        createFailed = result.created == null && result.problem != null
        if (forVisit == visit) {
            val now = flow.value
            flow.value = result.copy(friends = now.friends, mode = now.mode, name = now.name)
        }
    }

    private suspend fun afterCreation(
        creation: GroupCreation,
        names: List<String>,
        groupName: String?,
    ): NewChatState {
        val id = creation.groupId?.let(::KeyBytes)
        return when {
            id == null -> {
                NewChatState(loaded = true, selected = names.toSet(), problem = NewChatProblem.CANT_REACH)
            }

            creation.outcome !is SyncOutcome.Sent -> {
                NewChatState(
                    loaded = true,
                    selected = names.toSet(),
                    created = id,
                    // The first message is not confirmed: nobody else is added yet, and the user is told who is missing.
                    notAdded = names.drop(1),
                    problem = NewChatProblem.UNFINISHED,
                )
            }

            else -> {
                addTheRest(id, names, groupName)
            }
        }
    }

    private suspend fun addTheRest(
        id: KeyBytes,
        names: List<String>,
        groupName: String?,
    ): NewChatState {
        val missed = mutableListOf<String>()
        for (name in names.drop(1)) {
            val added = sessions.withSessionResult { chat.addToNewGroup(it, id, name) }
            if (added !is AuthResult.Ok || added.value !is SyncOutcome.Sent) missed += name
        }
        // The name goes last: a member only reads what was sent after they joined, so every friend who was added is in by now.
        // It counts as the chat's name only once the server accepted it (a failure leaves a message the conversation resends).
        val named = groupName?.let { sessions.withSessionResult { session -> chat.rename(session, id, it) } }
        val nameMissing = named != null && (named !is AuthResult.Ok || named.value !is SyncOutcome.Sent)
        return NewChatState(
            mode = if (groupName != null) NewChatMode.GROUP else NewChatMode.DIRECT,
            name = groupName.orEmpty(),
            loaded = true,
            selected = names.toSet(),
            created = id,
            notAdded = missed,
            problem =
                when {
                    missed.isNotEmpty() -> NewChatProblem.PARTLY
                    nameMissing -> NewChatProblem.NAME_NOT_SENT
                    else -> null
                },
        )
    }

    companion object {
        const val MAX_FRIENDS = 9

        /** More than the 64 characters of a name is cut as typed; the rules (B3a) decide the rest. */
        const val MAX_NAME_INPUT = 200
    }
}

private fun app.gifitup.shared.domain.auth.AuthError.toNewChatProblem(): NewChatProblem =
    when (toChatProblem()) {
        ChatProblem.OFFLINE -> NewChatProblem.OFFLINE
        ChatProblem.NOT_SIGNED_IN -> NewChatProblem.NOT_SIGNED_IN
        ChatProblem.FAILED, ChatProblem.HISTORY -> NewChatProblem.FAILED
    }
