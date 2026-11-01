package app.gifitup.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.gifitup.shared.domain.messages.CaptionInput
import app.gifitup.shared.presentation.chat.ChatProblem
import app.gifitup.shared.presentation.chat.ConversationState
import app.gifitup.shared.presentation.chat.ConversationStateHolder
import app.gifitup.shared.presentation.chat.ConversationTitle
import app.gifitup.shared.presentation.chat.GifTilesStateHolder
import app.gifitup.shared.presentation.chat.SendProblem
import app.gifitup.shared.presentation.chat.isGroup
import app.gifitup.shared.presentation.chat.listKey
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter

/**
 * One conversation: renders the holders' state and sends the user's actions to them; it decides nothing (ADR-0013). It
 * reads for new messages when it opens and then every [REFRESH_MILLIS] while it is on screen. Leaving drops the state
 * of the conversation and of the GIFs on screen.
 */
@Composable
internal fun ConversationScreen(
    holder: ConversationStateHolder,
    tiles: GifTilesStateHolder,
    picker: PickerParts,
    onOpenProfile: (String) -> Unit,
    onBack: (() -> Unit)?,
) {
    val state by holder.state.collectAsState()
    var pickerOpen by rememberSaveable { mutableStateOf(false) }
    // What was typed stays while the picker is over the screen; it is not kept in saved state (nothing the user writes is).
    var caption by remember { mutableStateOf("") }
    LaunchedEffect(holder) {
        holder.open()
        while (true) {
            delay(REFRESH_MILLIS)
            holder.refresh()
        }
    }
    DisposableEffect(holder) {
        onDispose {
            holder.leave()
            tiles.leave()
        }
    }
    val list = rememberLazyListState()
    // Newest at the bottom: the list is laid out from the bottom up, and a new message scrolls it into view.
    // Pinned: the reader is at the newest message. Only the user's own scrolling changes it, so a message that arrives
    // while they read older ones does not pull them down.
    var pinned by remember { mutableStateOf(true) }
    LaunchedEffect(list) {
        snapshotFlow { list.isScrollInProgress }.filter { !it }.collect {
            pinned =
                list.firstVisibleItemIndex == 0 && list.firstVisibleItemScrollOffset == 0
        }
    }
    LaunchedEffect(state.messages.size) {
        if (pinned && state.messages.isNotEmpty()) list.scrollToItem(0)
    }
    Box(Modifier.fillMaxSize()) {
        ConversationColumn(
            holder,
            state,
            tiles,
            list,
            SendInput(caption, {
                caption = it
            }, { pickerOpen = true }),
            Navigation(onOpenProfile, onBack),
        )
        if (pickerOpen) {
            val close = {
                pickerOpen = false
                picker.picker.close()
                picker.previews.leave()
            }
            // Over the whole conversation; the screen below keeps its state (the caption, the scroll position).
            Surface(Modifier.fillMaxSize()) {
                GifPickerScreen(picker, onClose = close) { reference ->
                    holder.send(reference, caption.ifBlank { null })
                    caption = ""
                    close()
                }
            }
        }
    }
}

@Suppress("LongParameterList") // The screen's pieces, handed down from the one place that owns them.
@Composable
private fun ConversationColumn(
    holder: ConversationStateHolder,
    state: ConversationState,
    tiles: GifTilesStateHolder,
    list: LazyListState,
    input: SendInput,
    nav: Navigation,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ConversationHeader(state, nav)
        state.conversationProblem?.let {
            Text(
                ChatTexts.problem(it),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.polite(),
            )
        }
        HistoryNotices(state, holder)
        if (state.loaded && state.messages.isEmpty()) Text(ChatTexts.NO_MESSAGES)
        val tileStates by tiles.tiles.collectAsState()
        LazyColumn(
            state = list,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            reverseLayout = true,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.messages.asReversed(), key = { it.reference.listKey() }) { message ->
                MessageRow(message, tiles, tileStates[message.reference])
            }
        }
        SendArea(holder, state, input)
    }
}

/** What the history has to say: a refresh problem, a broken history with its retry, unreadable records, an unconfirmed message. */
@Composable
private fun HistoryNotices(
    state: ConversationState,
    holder: ConversationStateHolder,
) {
    state.problem?.let { Text(ChatTexts.listProblem(it), color = MaterialTheme.colorScheme.error, modifier = Modifier.polite()) }
    if (state.problem == ChatProblem.HISTORY) TextButton(onClick = holder::retryHistory) { Text(ChatTexts.HISTORY_RETRY) }
    if (state.canResetHistory) ResetHistoryButton(holder::resetHistory)
    if (state.resetFailed) Text(ChatTexts.RESET_FAILED, color = MaterialTheme.colorScheme.error, modifier = Modifier.polite())
    if (state.unreadable > 0) Text(ConversationTexts.unreadable(state.unreadable), color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (state.hasUnsettled && state.conversationProblem != null) {
        Text(ConversationTexts.NOT_CONFIRMED, color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun ConversationHeader(
    state: ConversationState,
    nav: Navigation,
) {
    // A direct chat has one friend whose profile opens from an info button (#217); a group has none.
    val friend = state.members.singleOrNull()?.takeIf { state.title is ConversationTitle.Members }
    AppTopBar(
        title = ChatTexts.title(state.title),
        onBack = nav.onBack,
        subtitle = ConversationTexts.subtitle(state.members, state.title is ConversationTitle.Named),
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (state.refreshing) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                MemberAvatars(state.members)
                friend?.let { IconTile(ProfileTexts.OPEN_PROFILE, { nav.onOpenProfile(it) }) { InfoIcon(it) } }
            }
        },
    )
}

/** Where the conversation screen can go: back, and a friend's profile. */
private class Navigation(
    val onOpenProfile: (String) -> Unit,
    val onBack: (() -> Unit)?,
)

/** What the send area needs from the screen: the caption text (kept there while the picker is open) and how to open the picker. */
private class SendInput(
    val caption: String,
    val onCaption: (String) -> Unit,
    val onChoose: () -> Unit,
)

/** The caption field, the button that opens the GIF picker (#23) and what a send needs to say. */
@Composable
private fun SendArea(
    holder: ConversationStateHolder,
    state: ConversationState,
    input: SendInput,
) {
    val problem = state.sendProblem
    val unsettled = state.hasUnsettled
    val canSend = state.canSend
    val caption = input.caption
    var open by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        problem?.let { Text(ConversationTexts.sendProblem(it), color = MaterialTheme.colorScheme.error, modifier = Modifier.polite()) }
        if (unsettled) TextButton(onClick = holder::resend, enabled = canSend) { Text(ConversationTexts.RESEND) }
        if (open) {
            CaptionField(holder.captions, caption, onChange = input.onCaption, onRemove = {
                input.onCaption("")
                open = false
            })
        } else {
            TextButton(onClick = { open = true }) { Text(ConversationTexts.ADD_CAPTION) }
        }
        PlusButton(ConversationTexts.SEND, enabled = canSend, onClick = input.onChoose)
    }
}

@Composable
private fun CaptionField(
    rules: CaptionInput,
    caption: String,
    onChange: (String) -> Unit,
    onRemove: () -> Unit,
) {
    val check = rules.check(caption)
    val problem = ConversationTexts.captionProblem(check.verdict)
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = caption,
            onValueChange = onChange,
            label = { Text(ConversationTexts.CAPTION_LABEL) },
            singleLine = true,
            isError = problem != null,
            supportingText = {
                Text(
                    listOfNotNull(problem, ConversationTexts.counter(check.graphemes, CaptionInput.MAX_GRAPHEMES)).joinToString(" · "),
                )
            },
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onRemove) { Text(ConversationTexts.REMOVE_CAPTION) }
    }
}

private const val REFRESH_MILLIS = 15_000L

/** A change of this text is announced by a screen reader. */
internal fun Modifier.polite(): Modifier = semantics { liveRegion = LiveRegionMode.Polite }
