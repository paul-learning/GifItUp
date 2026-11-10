package app.gifitup.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.gifitup.shared.presentation.chat.GroupInfoProblem
import app.gifitup.shared.presentation.chat.GroupInfoState
import app.gifitup.shared.presentation.chat.GroupInfoStateHolder
import app.gifitup.shared.presentation.chat.MemberStanding

/**
 * A group's info (#217 part 4b): its name, its members and, for a group, the friends who can be added. Renders the
 * holder's state and forwards actions; it decides nothing (ADR-0013).
 */
@Composable
internal fun GroupInfoScreen(
    holder: GroupInfoStateHolder,
    onBack: () -> Unit,
    onLeft: () -> Unit,
) {
    val state by holder.state.collectAsState()
    LaunchedEffect(holder) { holder.open() }
    DisposableEffect(holder) { onDispose { holder.leave() } }
    LaunchedEffect(state.left) { if (state.left) onLeft() }
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AppTopBar(state.name?.let { ChatTexts.isolated(it) } ?: GroupInfoTexts.TITLE, onBack, backEnabled = !state.adding)
        if (!state.loaded) CircularProgressIndicator()
        Text(GroupInfoTexts.MEMBERS, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp))
        if (state.standingUnknown) {
            Text(
                GroupInfoTexts.STANDING_UNKNOWN,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.polite(),
            )
        }
        state.members.forEach { MemberRow(it, state.standing[it]) }
        if (state.loaded && state.canAdd) AddSection(state, holder)
        LeaveSection(state, holder)
    }
}

/** "Leave this chat", behind a confirmation that says what is deleted (#279). */
@Composable
private fun LeaveSection(
    state: GroupInfoState,
    holder: GroupInfoStateHolder,
) {
    var asking by rememberSaveable { mutableStateOf(false) }
    if (state.problem == GroupInfoProblem.LEAVE_FAILED) {
        Text(GroupInfoTexts.problem(GroupInfoProblem.LEAVE_FAILED), color = MaterialTheme.colorScheme.error, modifier = Modifier.polite())
    }
    TextButton(onClick = { asking = true }, enabled = !state.adding) {
        Text(GroupInfoTexts.LEAVE, color = MaterialTheme.colorScheme.error)
    }
    if (asking) {
        AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text(GroupInfoTexts.LEAVE_TITLE) },
            text = { Text(GroupInfoTexts.LEAVE_BODY) },
            confirmButton = {
                TextButton(
                    onClick = {
                        asking = false
                        holder.leaveChat()
                    },
                ) { Text(GroupInfoTexts.LEAVE_CONFIRM, color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { asking = false }) { Text(GroupInfoTexts.LEAVE_CANCEL) } },
        )
    }
}

@Composable
private fun MemberRow(
    name: String,
    standing: MemberStanding?,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Avatar(name)
        Column {
            Text(ChatTexts.isolated(name), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            // By key against the saved pin, never by name alone: a stranger can carry a friend's name (#31).
            GroupInfoTexts.standing(standing, name)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
            }
        }
    }
}

/** The friends who are not in the group, with a checkbox each, and the button that adds the chosen ones. */
@Composable
private fun AddSection(
    state: GroupInfoState,
    holder: GroupInfoStateHolder,
) {
    Text(GroupInfoTexts.ADD, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
    if (state.candidates.isEmpty()) Text(GroupInfoTexts.NOBODY_TO_ADD, color = MaterialTheme.colorScheme.onSurfaceVariant)
    state.candidates.forEach { name -> FriendRow(name, name in state.selected, !state.adding) { holder.toggle(name) } }
    state.problem?.let { Text(GroupInfoTexts.problem(it), color = MaterialTheme.colorScheme.error, modifier = Modifier.polite()) }
    if (state.notAdded.isNotEmpty()) Text(GroupInfoTexts.NOT_ADDED + state.notAdded.joinToString(", ") { ChatTexts.isolated(it) })
    if (state.adding) CircularProgressIndicator()
    if (state.candidates.isNotEmpty()) PrimaryButton(GroupInfoTexts.ADD_BUTTON, onClick = holder::add, enabled = state.canSubmit)
    if (state.problem == GroupInfoProblem.NAME_NOT_SENT) {
        SecondaryButton(ChatTexts.SEND_NAME_AGAIN, onClick = holder::retryName, enabled = !state.adding)
    }
}

internal object GroupInfoTexts {
    const val TITLE = "Group"
    const val MEMBERS = "Members"
    const val ADD = "Add friends"
    const val ADD_BUTTON = "Add to the group"
    const val NOBODY_TO_ADD = "All your friends are in this group already."
    const val STANDING_UNKNOWN = "This device can't tell right now which members are your friends."
    const val NOT_ADDED = "Not added: "
    const val LEAVE = "Leave this chat"
    const val LEAVE_TITLE = "Leave this chat?"
    const val LEAVE_BODY =
        "You leave the chat and its messages are deleted from this device. The others keep theirs, and may still see you in " +
            "the member list until one of them removes you."
    const val LEAVE_CONFIRM = "Leave and delete"
    const val LEAVE_CANCEL = "Stay"

    /** The note under a member who is not a saved friend; `null` for a friend or while it isn't known. */
    fun standing(
        standing: MemberStanding?,
        name: String,
    ): String? =
        when (standing) {
            MemberStanding.NOT_SAVED -> "Not in your friends"
            MemberStanding.OTHER_KEY -> "Not the ${ChatTexts.isolated(name)} you saved: another key"
            MemberStanding.FRIEND, null -> null
        }

    fun problem(problem: GroupInfoProblem): String =
        when (problem) {
            GroupInfoProblem.FRIENDS_UNAVAILABLE -> "This device's friends list can't be used."
            GroupInfoProblem.NOBODY -> "Choose at least one friend."
            GroupInfoProblem.PARTLY -> "Not everyone could be added. They may need to open the app once; you can try again."
            GroupInfoProblem.NAME_NOT_SENT -> "The friends were added, but the group's name was not sent. Until it is, they may not see it."
            GroupInfoProblem.OFFLINE -> "No answer from the server."
            GroupInfoProblem.NOT_SIGNED_IN -> "The server didn't accept this device."
            GroupInfoProblem.THROTTLED -> ChatTexts.THROTTLED_SHORT
            GroupInfoProblem.FAILED -> "That did not work."
            GroupInfoProblem.LEAVE_FAILED -> "Leaving did not work. The chat is still here; you can try again."
        }
}
