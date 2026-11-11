package app.gifitup.ui

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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import app.gifitup.shared.presentation.chat.ChatListState
import app.gifitup.shared.presentation.chat.ChatListStateHolder
import app.gifitup.shared.presentation.chat.ChatProblem
import app.gifitup.shared.presentation.chat.ConversationSummary
import app.gifitup.shared.presentation.chat.avatarName
import app.gifitup.shared.presentation.chat.listKey
import kotlinx.coroutines.delay
import kotlin.time.Clock

/**
 * The chat list: renders the holder's state and sends the user's actions to it; it decides nothing (ADR-0013). It reads
 * for new messages when it opens and when the push channel says something arrived (#27), and as a fallback every
 * [REFRESH_MILLIS] ([CONNECTED_REFRESH_MILLIS] while the socket is connected) while it is on screen. Leaving the screen drops its state.
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
    // The timer is the fallback of the push channel (#27): slow while the socket is connected.
    val refreshMillis by rememberUpdatedState(if (actions.liveConnected) CONNECTED_REFRESH_MILLIS else REFRESH_MILLIS)
    LaunchedEffect(holder) {
        while (true) {
            holder.refresh()
            delay(refreshMillis)
        }
    }
    DisposableEffect(holder) { onDispose { holder.leave() } }
    Box(modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AppTopBar(
                title = ChatTexts.TITLE,
                onBack = null,
                prominent = true,
                leading = { BurgerMenu(actions.onMenu) },
                trailing = { TopBarActions(actions.customServer, actions.onMenu) },
            )
            notice?.invoke()
            FilterRow(state.filter, holder::showOnly)
            if (state.refreshing) RefreshingLine()
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
            ListNotices(state)
            LazyColumn(modifier = Modifier.weight(1f)) {
                requestItems(state, holder)
                items(state.visible, key = { it.id.listKey() }) { row ->
                    ConversationRow(row, actions, isSelected = row.id == selected)
                }
                // Room under the last row for the round "+" that floats over the list.
                item { Spacer(Modifier.height(FAB_ROOM)) }
            }
        }
        Box(
            Modifier.align(Alignment.BottomEnd).padding(24.dp),
        ) { PlusButton(ChatTexts.NEW_CHAT, enabled = true, onClick = actions.onNewChat) }
    }
}

/** A small spinner with "Refreshing" while the list reads from the server. */
@Composable
private fun RefreshingLine() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        Text(ChatTexts.REFRESHING, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** All, direct chats, groups as plain tabs: the chosen one is bright with a short purple line under it. */
@Composable
private fun FilterRow(
    filter: ChatFilter,
    onChoose: (ChatFilter) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        listOf(
            ChatFilter.ALL to ChatTexts.FILTER_ALL,
            ChatFilter.DIRECT to ChatTexts.FILTER_DIRECT,
            ChatFilter.GROUPS to ChatTexts.FILTER_GROUPS,
        ).forEach { (choice, label) ->
            val chosen = filter == choice
            Column(
                Modifier
                    .defaultMinSize(minHeight = TAB_HEIGHT)
                    .minimumInteractiveComponentSize()
                    .selectable(selected = chosen, role = Role.Tab, onClick = { onChoose(choice) }),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (chosen) colors.onSurface else colors.onSurfaceVariant,
                )
                Box(
                    Modifier
                        .padding(top = 4.dp)
                        .size(width = TAB_LINE, height = 2.dp)
                        .background(if (chosen) colors.primary else Color.Transparent),
                )
            }
        }
    }
}

/** The Requests section at the top of the list: a heading and one row per request. */
private fun LazyListScope.requestItems(
    state: ChatListState,
    holder: ChatListStateHolder,
) {
    if (state.requests.isEmpty()) return
    item(key = "requests-header") { Text(ChatTexts.REQUESTS, style = MaterialTheme.typography.titleMedium) }
    items(state.requests, key = { "request-" + it.id.listKey() }) { row ->
        RequestRow(row.info.requestedBy.orEmpty(), { holder.acceptRequest(row.id) }, { holder.leaveChat(row.id) })
    }
}

/** What the welcome check and the last action have to say: chats waiting for a slot, requests dropped, an action that failed. */
@Composable
private fun ListNotices(state: ChatListState) {
    if (state.welcomesWaiting > 0) Text(ChatTexts.welcomesWaiting(state.welcomesWaiting), modifier = Modifier.polite())
    if (state.requestsDropped > 0) Text(ChatTexts.requestsDropped(state.requestsDropped), modifier = Modifier.polite())
    if (state.actionFailed) Text(ChatTexts.ACTION_FAILED, color = MaterialTheme.colorScheme.error, modifier = Modifier.polite())
}

/** A chat a non-friend started (#279): the sender's name and the two answers; nothing of the chat itself. */
@Composable
private fun RequestRow(
    name: String,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
) {
    CardSurface {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Avatar(name)
            Text(ChatTexts.requestFrom(name), style = MaterialTheme.typography.bodyMedium)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton(ChatTexts.ACCEPT, onAccept, Modifier.weight(1f))
            SecondaryButton(ChatTexts.DECLINE, onDecline, Modifier.weight(1f))
        }
    }
}

/** One chat as a flat row with a hairline under it: avatar, title, the last message as a line, the time and the unread count. */
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
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(if (isSelected) colors.secondaryContainer else Color.Transparent, MaterialTheme.shapes.medium)
                .clip(MaterialTheme.shapes.medium)
                .then(clickable)
                .semantics(mergeDescendants = true) {
                    contentDescription = ChatTexts.rowDescription(title, preview, ago, row.unread)
                    selected = isSelected
                },
    ) {
        Row(
            modifier = Modifier.defaultMinSize(minHeight = 68.dp).padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // On the highlighted row the usual avatar color is the row's own color; the plain surface stands out.
            Avatar(row.title.avatarName, color = if (isSelected) colors.surface else colors.surfaceContainerHigh)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (row.unread > 0) FontWeight.SemiBold else FontWeight.Medium,
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
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ago?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant) }
                if (row.unread > 0) UnreadBadge(ChatTexts.unread(row.unread))
            }
        }
        // The hairline starts under the text, not under the avatar.
        HorizontalDivider(Modifier.padding(start = DIVIDER_INSET), color = colors.outlineVariant)
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

/** A slim purple pill with the unread count (the row's description says it for a screen reader). */
@Composable
private fun UnreadBadge(count: String) {
    Text(
        count,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onPrimary,
        modifier = Modifier.background(MaterialTheme.colorScheme.primary, CircleShape).padding(horizontal = 7.dp, vertical = 1.dp),
    )
}

private const val REFRESH_MILLIS = 15_000L
private const val CONNECTED_REFRESH_MILLIS = 30_000L
private val FAB_ROOM = 88.dp
private val TAB_HEIGHT = 36.dp
private val TAB_LINE = 28.dp
private val DIVIDER_INSET = 56.dp
