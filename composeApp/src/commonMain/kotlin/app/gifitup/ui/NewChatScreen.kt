package app.gifitup.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.gifitup.shared.presentation.chat.NewChatProblem
import app.gifitup.shared.presentation.chat.NewChatState
import app.gifitup.shared.presentation.chat.NewChatStateHolder

/** The new-chat flow: renders the holder's state and forwards the user's actions; it decides nothing (ADR-0013). */
@Composable
internal fun NewChatScreen(
    holder: NewChatStateHolder,
    onDone: () -> Unit,
    onBack: () -> Unit,
) {
    val state by holder.state.collectAsState()
    LaunchedEffect(holder) { holder.open() }
    DisposableEffect(holder) { onDispose { holder.leave() } }
    LaunchedEffect(state.created, state.problem) {
        // A chat that is made and complete goes back to the list; one with a problem stays so the user reads it.
        if (state.created != null && state.problem == null) onDone()
    }
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(ChatTexts.NEW_CHAT, style = MaterialTheme.typography.titleLarge)
        Text(ChatTexts.NEW_CHAT_HINT)
        if (!state.loaded) CircularProgressIndicator()
        if (state.loaded && state.friends.isEmpty() && state.problem != NewChatProblem.FRIENDS_UNAVAILABLE) Text(ChatTexts.NO_FRIENDS)
        state.friends.forEach { name ->
            FriendRow(name, name in state.selected, !state.creating && state.created == null) { holder.toggle(name) }
        }
        Notices(state)
        if (state.creating) CircularProgressIndicator()
        Button(onClick = holder::create, enabled = state.canCreate) { Text(ChatTexts.START_CHAT) }
        TextButton(onClick = if (state.created != null) onDone else onBack, enabled = !state.creating) {
            Text(if (state.created != null) "Done" else ChatTexts.BACK)
        }
    }
}

@Composable
private fun FriendRow(
    name: String,
    checked: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 48.dp)
                .toggleable(value = checked, enabled = enabled, role = Role.Checkbox) { onToggle() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Text(ChatTexts.isolated(name), modifier = Modifier.padding(start = 12.dp))
    }
}

/** What went wrong or only partly worked, in words. */
@Composable
private fun Notices(state: NewChatState) {
    state.problem?.let { Text(ChatTexts.newChatProblem(it), color = MaterialTheme.colorScheme.error) }
    if (state.notAdded.isNotEmpty()) Text("Not added: " + state.notAdded.joinToString(", ") { ChatTexts.isolated(it) })
    if (state.created != null && state.problem != null) Text(ChatTexts.CREATED)
}
