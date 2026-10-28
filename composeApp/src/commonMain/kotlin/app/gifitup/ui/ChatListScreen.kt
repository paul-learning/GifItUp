package app.gifitup.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.gifitup.shared.domain.contacts.KeyBytes
import app.gifitup.shared.domain.groups.GroupInfo
import app.gifitup.shared.presentation.chat.Ago
import app.gifitup.shared.presentation.chat.ChatListStateHolder
import app.gifitup.shared.presentation.chat.ConversationSummary
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
        modifier = modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(ChatTexts.TITLE, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            Button(onClick = actions.onNewChat) { Text(ChatTexts.NEW_CHAT) }
        }
        if (state.refreshing) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(modifier = Modifier.defaultMinSize(minWidth = 16.dp, minHeight = 16.dp), strokeWidth = 2.dp)
                Text(ChatTexts.REFRESHING, style = MaterialTheme.typography.bodySmall)
            }
        }
        state.problem?.let {
            Text(
                ChatTexts.listProblem(it),
                color = MaterialTheme.colorScheme.error,
                modifier =
                    Modifier.semantics {
                        liveRegion =
                            LiveRegionMode.Polite
                    },
            )
        }
        if (state.loaded && state.conversations.isEmpty()) Text(ChatTexts.NO_CHATS)
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(state.conversations, key = { it.id.listKey() }) { row ->
                ConversationRow(row, actions.onOpen, selected = row.id == selected)
                HorizontalDivider()
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = actions.onFriends) { Text("Friends") }
            TextButton(onClick = onBack) { Text(ChatTexts.BACK) }
        }
    }
}

@Composable
private fun ConversationRow(
    row: ConversationSummary,
    onOpen: ((GroupInfo) -> Unit)?,
    selected: Boolean,
) {
    val title = ChatTexts.title(row.title)
    val preview = row.problem?.let(ChatTexts::problem) ?: ChatTexts.preview(row.preview)
    val ago = row.lastAt?.let { ChatTexts.ago(Ago.of(Clock.System.now().epochSeconds, it)) }
    val clickable = if (onOpen != null) Modifier.clickable(role = Role.Button) { onOpen(row.info) } else Modifier
    val background = if (selected) Modifier.background(MaterialTheme.colorScheme.secondaryContainer) else Modifier
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .then(background)
                .then(clickable)
                .defaultMinSize(minHeight = 56.dp)
                .padding(vertical = 8.dp)
                .semantics(mergeDescendants = true) {
                    contentDescription = ChatTexts.rowDescription(title, preview, ago, row.unread)
                    this.selected = selected
                },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (row.unread > 0) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                preview,
                style = MaterialTheme.typography.bodyMedium,
                color = if (row.problem != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            ago?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (row.unread > 0) Badge { Text(ChatTexts.unread(row.unread)) }
        }
    }
}

private const val REFRESH_MILLIS = 15_000L
