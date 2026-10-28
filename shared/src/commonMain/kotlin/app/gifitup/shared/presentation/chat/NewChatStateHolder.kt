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

    /** The group was made; some of the other friends are in [NewChatState.notAdded] and can be added from the conversation. */
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
                NewChatState(loaded = true, problem = NewChatProblem.FRIENDS_UNAVAILABLE)
            } else {
                current.copy(
                    loaded = true,
                    friends = names.sorted(),
                    selected = current.selected.filter { it in names }.toSet(),
                    problem =
                        problem ?: current.problem.takeIf { current.created != null },
                )
            }
    }

    fun toggle(name: String) {
        val current = flow.value
        if (name !in current.friends || current.creating || current.created != null) return
        val next = if (name in current.selected) current.selected - name else current.selected + name
        if (next.size <= MAX_FRIENDS) flow.value = current.copy(selected = next, problem = null)
    }

    fun create() {
        val current = flow.value
        if (running || !current.canCreate) {
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
                if (forVisit == visit) {
                    running = false
                    flow.value = flow.value.copy(creating = false)
                }
            }
        }
    }

    fun leave() {
        visit++
        running = false
        flow.value = NewChatState()
    }

    private suspend fun make(
        forVisit: Int,
        names: List<String>,
    ) {
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
        ChatProblem.FAILED -> NewChatProblem.FAILED
    }
