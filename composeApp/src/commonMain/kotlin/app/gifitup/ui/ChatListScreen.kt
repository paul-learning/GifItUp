package app.gifitup.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import app.gifitup.shared.domain.chat.ChatKind
import app.gifitup.shared.domain.contacts.KeyBytes
import app.gifitup.shared.presentation.chat.Ago
import app.gifitup.shared.presentation.chat.ChatFilter
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
    notice: (@Composable () -> Unit)? = null,
) {
    val state by holder.state.collectAsState()
    LaunchedEffect(holder) {
        while (true) {
            holder.refresh()
            delay(REFRESH_MILLIS)
        }
    }
    DisposableEffect(holder) { onDispose { holder.leave() } }
    Box(modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AppTopBar(title = ChatTexts.TITLE, onBack = null, trailing = { BurgerMenu(actions.onMenu) })
            notice?.invoke()
            FilterRow(state.filter, holder::showOnly)
            if (state.refreshing) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text(
                        ChatTexts.REFRESHING,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            state.problem?.let {
                Text(ChatTexts.listProblem(it), color = MaterialTheme.colorScheme.error, modifier = Modifier.polite())
                if (it == ChatProblem.HISTORY) TextButton(onClick = holder::retryHistory) { Text(ChatTexts.HISTORY_RETRY) }
                if (state.canResetHistory) ResetHistoryButton(holder::resetHistory)
                if (state.resetFailed) Text(ChatTexts.RESET_FAILED, color = MaterialTheme.colorScheme.error, modifier = Modifier.polite())
            }
            if (state.loaded && state.visible.isEmpty()) {
                Text(
                    ChatTexts.emptyList(state.filter, anyChats = state.conversations.isNotEmpty()),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(state.visible, key = { it.id.listKey() }) { row ->
                    ConversationRow(row, actions, isSelected = row.id == selected)
                }
                // Room under the last row for the round "+" that floats over the list.
                item { Spacer(Modifier.height(FAB_ROOM)) }
            }
        }
        Box(
            Modifier.align(Alignment.BottomEnd).padding(20.dp),
        ) { PlusButton(ChatTexts.NEW_CHAT, enabled = true, onClick = actions.onNewChat) }
    }
}

/** The burger menu of the list's top bar: profile, invite a friend, friends. */
@Composable
private fun BurgerMenu(actions: MenuActions) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconTile(ChatTexts.MENU, { open = true }) { MenuIcon(it) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            listOf(
                ChatTexts.PROFILE to actions.onProfile,
                ChatTexts.INVITE to actions.onInvite,
                ChatTexts.FRIENDS to actions.onFriends,
            ).forEach { (label, go) ->
                DropdownMenuItem(text = { Text(label) }, onClick = {
                    open = false
                    go()
                })
            }
        }
    }
}

/** All, direct chats, groups: one is selected, and the list shows only what it lets through. */
@Composable
private fun FilterRow(
    filter: ChatFilter,
    onChoose: (ChatFilter) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(
            ChatFilter.ALL to ChatTexts.FILTER_ALL,
            ChatFilter.DIRECT to ChatTexts.FILTER_DIRECT,
            ChatFilter.GROUPS to ChatTexts.FILTER_GROUPS,
        ).forEach { (choice, label) ->
            FilterChip(selected = filter == choice, onClick = { onChoose(choice) }, label = { Text(label) })
        }
    }
}

/** One chat as a card: avatar, title, the last message as a line, the time and the unread count. */
@Composable
private fun ConversationRow(
    row: ConversationSummary,
    actions: ChatListActions,
    isSelected: Boolean,
) {
    val colors = MaterialTheme.colorScheme
    val title = ChatTexts.title(row.title)
    val preview = if (row.historyProblem) ChatTexts.HISTORY_ROW else row.problem?.let(ChatTexts::problem) ?: ChatTexts.preview(row.preview)
    val ago = row.lastAt?.let { ChatTexts.ago(Ago.of(Clock.System.now().epochSeconds, it)) }
    val clickable = rowClick(row, actions)
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

/** A tap opens the chat; a long tap on a direct chat opens the friend's profile (#217), a group has no single profile. */
@OptIn(ExperimentalFoundationApi::class)
private fun rowClick(
    row: ConversationSummary,
    actions: ChatListActions,
): Modifier {
    val onOpen = actions.onOpen ?: return Modifier
    val friend = if (row.kind == ChatKind.DIRECT) row.info.others.singleOrNull() else null
    return Modifier.combinedClickable(
        role = Role.Button,
        onLongClickLabel = friend?.let { ProfileTexts.OPEN_PROFILE },
        onLongClick = friend?.let { name -> { actions.onProfile(name) } },
        onClick = { onOpen(row.info) },
    )
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
private val FAB_ROOM = 96.dp
