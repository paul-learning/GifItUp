package app.gifitup.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.gifitup.shared.domain.messages.CaptionInput
import app.gifitup.shared.presentation.chat.ConversationStateHolder
import app.gifitup.shared.presentation.chat.ConversationTitle
import app.gifitup.shared.presentation.chat.GifTilesStateHolder
import app.gifitup.shared.presentation.chat.SendProblem
import kotlinx.coroutines.delay

/**
 * One conversation: renders the holders' state and sends the user's actions to them; it decides nothing (ADR-0013). It
 * reads for new messages when it opens and then every [REFRESH_MILLIS] while it is on screen. Leaving drops the state
 * of the conversation and of the GIFs on screen.
 */
@Composable
internal fun ConversationScreen(
    holder: ConversationStateHolder,
    tiles: GifTilesStateHolder,
    onBack: () -> Unit,
) {
    val state by holder.state.collectAsState()
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
    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) list.scrollToItem(0)
    }
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onBack) { Text(ChatTexts.BACK) }
            Text(
                ChatTexts.title(state.title),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (state.refreshing) CircularProgressIndicator(modifier = Modifier.padding(4.dp), strokeWidth = 2.dp)
        }
        state.conversationProblem?.let { Text(ChatTexts.problem(it), color = MaterialTheme.colorScheme.error) }
        state.problem?.let { Text(ChatTexts.listProblem(it), color = MaterialTheme.colorScheme.error) }
        if (state.loaded && state.messages.isEmpty()) Text(ChatTexts.NO_MESSAGES)
        val tileStates by tiles.tiles.collectAsState()
        LazyColumn(
            state = list,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            reverseLayout = true,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.messages.asReversed(), key = { it.reference.bytes.toHex() }) { message ->
                MessageRow(message, tiles, tileStates[message.reference], state.title.isGroup())
            }
        }
        SendArea(holder, state.sendProblem, state.hasUnsettled, state.canSend)
    }
}

/** The caption field and the send button. The button is off until the GIF picker (#23) can hand over a GIF. */
@Composable
private fun SendArea(
    holder: ConversationStateHolder,
    problem: SendProblem?,
    unsettled: Boolean,
    canSend: Boolean,
) {
    var open by remember { mutableStateOf(false) }
    var caption by remember { mutableStateOf("") }
    problem?.let { Text(ConversationTexts.sendProblem(it), color = MaterialTheme.colorScheme.error) }
    if (unsettled) TextButton(onClick = holder::resend, enabled = canSend) { Text(ConversationTexts.RESEND) }
    if (open) {
        CaptionField(holder.captions, caption, onChange = { caption = it }, onRemove = {
            caption = ""
            open = false
        })
    } else {
        TextButton(onClick = { open = true }) { Text(ConversationTexts.ADD_CAPTION) }
    }
    Button(onClick = {}, enabled = false) { Text(ConversationTexts.SEND) }
    Text(ConversationTexts.SEND_NEEDS_PICKER, style = MaterialTheme.typography.bodySmall)
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

private fun ConversationTitle.isGroup(): Boolean = this is ConversationTitle.Named || (this as ConversationTitle.Members).names.size >= 2

internal fun ByteArray.toHex(): String = joinToString("") { it.toUByte().toString(HEX_RADIX).padStart(2, '0') }

private const val HEX_RADIX = 16
private const val REFRESH_MILLIS = 15_000L
