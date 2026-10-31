package app.gifitup.shared.presentation.chat

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

    OFFLINE,
    NOT_SIGNED_IN,
    FAILED,
}

data class NewChatState(
    val loaded: Boolean = false,
    val friends: List<String> = emptyList(),
    val selected: Set<String> = emptySet(),
    val creating: Boolean = false,
    /** The group that was made, `null` until then. */
    val created: KeyBytes? = null,
    val notAdded: List<String> = emptyList(),
    val problem: NewChatProblem? = null,
) {
    val canCreate: Boolean get() = selected.isNotEmpty() && !creating && created == null
}

/**
 * The new-chat flow (#25): pick friends, make a 1:1 chat (one friend) or a group (more). The first friend creates the
 * group, each further one is added with its own commit (ADR-0016). A friend that can't be added doesn't undo the group:
 * it is listed in [NewChatState.notAdded]. At most [MAX_FRIENDS] are chosen at once.
 */
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

    fun toggle(name: String) {
        val current = flow.value
        if (name !in current.friends || current.creating || current.created != null) return
        val next = if (name in current.selected) current.selected - name else current.selected + name
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
            if (current.selected.isEmpty()) flow.value = current.copy(problem = NewChatProblem.NOBODY)
            return
        }
        running = true
        val forVisit = visit
        flow.value = current.copy(creating = true, problem = null)
        scope.launch {
            try {
                make(forVisit, current.selected.sorted())
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
                    afterCreation(creation.value, names)
                }
            }
        createFailed = result.created == null && result.problem != null
        if (forVisit == visit) flow.value = result.copy(friends = flow.value.friends)
    }

    private suspend fun afterCreation(
        creation: GroupCreation,
        names: List<String>,
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
                addTheRest(id, names)
            }
        }
    }

    private suspend fun addTheRest(
        id: KeyBytes,
        names: List<String>,
    ): NewChatState {
        val missed = mutableListOf<String>()
        for (name in names.drop(1)) {
            val added = sessions.withSessionResult { chat.add(it, id, name) }
            if (added !is AuthResult.Ok || added.value !is SyncOutcome.Sent) missed += name
        }
        return NewChatState(
            loaded = true,
            selected = names.toSet(),
            created = id,
            notAdded = missed,
            problem = if (missed.isEmpty()) null else NewChatProblem.PARTLY,
        )
    }

    companion object {
        const val MAX_FRIENDS = 9
    }
}

private fun app.gifitup.shared.domain.auth.AuthError.toNewChatProblem(): NewChatProblem =
    when (toChatProblem()) {
        ChatProblem.OFFLINE -> NewChatProblem.OFFLINE
        ChatProblem.NOT_SIGNED_IN -> NewChatProblem.NOT_SIGNED_IN
        ChatProblem.FAILED, ChatProblem.HISTORY -> NewChatProblem.FAILED
    }
