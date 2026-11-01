package app.gifitup.ui

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.gifitup.shared.presentation.chat.NewChatMode
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
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AppTopBar(
            ChatTexts.NEW_CHAT,
            if (state.created != null) onDone else onBack,
            backLabel = if (state.created != null) ChatTexts.DONE else ChatTexts.BACK,
            backEnabled = !state.creating,
        )
        ModeAndName(state, holder)
        if (!state.loaded) CircularProgressIndicator()
        if (state.loaded && state.friends.isEmpty() && state.problem != NewChatProblem.FRIENDS_UNAVAILABLE) {
            Text(ChatTexts.NO_FRIENDS, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        state.friends.forEach { name ->
            FriendRow(name, name in state.selected, !state.creating && state.created == null) { holder.toggle(name) }
        }
        Notices(state)
        if (state.creating) CircularProgressIndicator()
        PrimaryButton(
            if (state.mode == NewChatMode.GROUP) ChatTexts.CREATE_GROUP else ChatTexts.START_CHAT,
            onClick = holder::create,
            enabled = state.canCreate,
        )
        if (state.created != null) SecondaryButton(ChatTexts.DONE, onClick = onDone, enabled = !state.creating)
    }
}

/** The two modes, what each needs, and the group's name field. */
@Composable
private fun ModeAndName(
    state: NewChatState,
    holder: NewChatStateHolder,
) {
    ModeRow(state, holder)
    Text(
        if (state.mode == NewChatMode.GROUP) ChatTexts.NEW_CHAT_HINT_GROUP else ChatTexts.NEW_CHAT_HINT_DIRECT,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (state.mode == NewChatMode.GROUP) {
        OutlinedTextField(
            value = state.name,
            onValueChange = holder::setName,
            label = { Text(ChatTexts.GROUP_NAME_LABEL) },
            singleLine = true,
            enabled = !state.creating && state.created == null,
            isError = state.problem == NewChatProblem.NAME_INVALID,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** A chat with one friend, or a group: two chips; a change is refused while something is being made. */
@Composable
private fun ModeRow(
    state: NewChatState,
    holder: NewChatStateHolder,
) {
    val enabled = !state.creating && state.created == null
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(NewChatMode.DIRECT to ChatTexts.MODE_DIRECT, NewChatMode.GROUP to ChatTexts.MODE_GROUP).forEach { (mode, label) ->
            FilterChip(selected = state.mode == mode, enabled = enabled, onClick = { holder.setMode(mode) }, label = { Text(label) })
        }
    }
}

/** A friend as a card with a checkbox: a tap on the card toggles it; selected ones have an accent outline. */
@Composable
private fun FriendRow(
    name: String,
    checked: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        color = colors.surfaceContainer,
        shape = MaterialTheme.shapes.large,
        border = if (checked) BorderStroke(2.dp, colors.secondary) else null,
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.large)
                .toggleable(value = checked, enabled = enabled, role = Role.Checkbox) { onToggle() },
    ) {
        Row(
            modifier = Modifier.defaultMinSize(minHeight = 64.dp).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Avatar(name)
            Text(
                ChatTexts.isolated(name),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            Checkbox(checked = checked, onCheckedChange = null)
        }
    }
}

/** What went wrong or only partly worked, in words. */
@Composable
private fun Notices(state: NewChatState) {
    state.problem?.let { Text(ChatTexts.newChatProblem(it), color = MaterialTheme.colorScheme.error, modifier = Modifier.polite()) }
    if (state.notAdded.isNotEmpty()) Text("Not added: " + state.notAdded.joinToString(", ") { ChatTexts.isolated(it) })
    if (state.created != null && state.problem != null) Text(ChatTexts.CREATED)
}
