package app.gifitup.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.gifitup.shared.domain.contacts.KeyBytes
import app.gifitup.shared.domain.groups.GroupInfo
import app.gifitup.shared.presentation.chat.Ago
import app.gifitup.shared.presentation.chat.ChatListStateHolder
import app.gifitup.shared.presentation.chat.ChatProblem
import app.gifitup.shared.presentation.chat.ConversationSummary
import app.gifitup.shared.presentation.chat.avatarName
import app.gifitup.shared.presentation.chat.listKey
import kotlinx.coroutines.delay
import kotlin.time.Clock

/**
 * The chat list: renders the holder's state and sends the user's actions to it; it decides nothing (ADR-0013). It reads
 * for new messages when it opens and then every [REFRESH_MILLIS] while it is on screen (polling; push comes with a
 * socket client later). Leaving the screen drops its state.
 */
@Composable
internal fun ChatListScreen(
    holder: ChatListStateHolder,
    actions: ChatListActions,
    modifier: Modifier = Modifier,
    selected: KeyBytes? = null,
    onBack: () -> Unit,
) {
    val state by holder.state.collectAsState()
    LaunchedEffect(holder) {
        while (true) {
            holder.refresh()
            delay(REFRESH_MILLIS)
        }
    }
    DisposableEffect(holder) { onDispose { holder.leave() } }
    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AppTopBar(
            title = ChatTexts.TITLE,
            onBack = onBack,
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IconTile(ChatTexts.FRIENDS, actions.onFriends) { PeopleIcon(it) }
                    Button(onClick = actions.onNewChat, shape = MaterialTheme.shapes.medium, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(ChatTexts.NEW_CHAT, fontWeight = FontWeight.Bold)
                    }
                }
            },
        )
        if (state.refreshing) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Text(ChatTexts.REFRESHING, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        state.problem?.let {
            Text(
                ChatTexts.listProblem(it),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.polite(),
            )
            if (it == ChatProblem.HISTORY) TextButton(onClick = holder::retryHistory) { Text(ChatTexts.HISTORY_RETRY) }
            if (state.canResetHistory) ResetHistoryButton(holder::resetHistory)
            if (state.resetFailed) Text(ChatTexts.RESET_FAILED, color = MaterialTheme.colorScheme.error, modifier = Modifier.polite())
        }
        if (state.loaded && state.conversations.isEmpty()) Text(ChatTexts.NO_CHATS, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(state.conversations, key = { it.id.listKey() }) { row ->
                ConversationRow(row, actions.onOpen, isSelected = row.id == selected)
            }
        }
    }
}

/** One chat as a card: avatar, title, the last message as a line, the time and the unread count. */
@Composable
private fun ConversationRow(
    row: ConversationSummary,
    onOpen: ((GroupInfo) -> Unit)?,
    isSelected: Boolean,
) {
    val colors = MaterialTheme.colorScheme
    val title = ChatTexts.title(row.title)
    val preview = if (row.historyProblem) ChatTexts.HISTORY_ROW else row.problem?.let(ChatTexts::problem) ?: ChatTexts.preview(row.preview)
    val ago = row.lastAt?.let { ChatTexts.ago(Ago.of(Clock.System.now().epochSeconds, it)) }
    val clickable = if (onOpen != null) Modifier.clickable(role = Role.Button) { onOpen(row.info) } else Modifier
    Surface(
        color = if (isSelected) colors.secondaryContainer else colors.surfaceContainer,
        shape = MaterialTheme.shapes.large,
        border = if (isSelected) BorderStroke(2.dp, colors.secondary) else null,
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.large)
                .then(clickable)
                .semantics(mergeDescendants = true) {
                    contentDescription = ChatTexts.rowDescription(title, preview, ago, row.unread)
                    selected = isSelected
                },
    ) {
        Row(
            modifier = Modifier.defaultMinSize(minHeight = 72.dp).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // On the highlighted row the usual avatar color is the row's own color; the plain surface stands out.
            Avatar(row.title.avatarName, color = if (isSelected) colors.surface else colors.surfaceContainerHigh)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (row.unread > 0) FontWeight.ExtraBold else FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    preview,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (row.problem != null || row.historyProblem) colors.error else colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ago?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant) }
                if (row.unread > 0) UnreadBadge(ChatTexts.unread(row.unread))
            }
        }
    }
}

@Composable
private fun UnreadBadge(count: String) {
    Text(
        count,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onPrimary,
        modifier = Modifier.background(MaterialTheme.colorScheme.primary, CircleShape).padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

private const val REFRESH_MILLIS = 15_000L
