package app.gifitup.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.gifitup.shared.data.content.GifReference
import app.gifitup.shared.domain.contacts.KeyBytes
import app.gifitup.shared.domain.messages.CaptionInput
import app.gifitup.shared.domain.messages.CaptionVerdict
import app.gifitup.shared.presentation.chat.GifTilesStateHolder
import app.gifitup.shared.presentation.chat.TileGeometry

/**
 * The step after picking (#217, Signal-style in #154): the chosen GIF **fitted** into the room above the caption bar (never taller
 * than that room, so it can't overlap the bar), a rounded caption bar docked at the bottom with a round send button, above the
 * keyboard. The caption is optional and Send is one tap; the keyboard's own send key sends too. "Back" returns to the results.
 * Renders state and decides nothing (ADR-0013): the caption rules are [CaptionInput]'s.
 */
@Composable
internal fun GifSendScreen(
    reference: GifReference,
    tiles: GifTilesStateHolder,
    rules: CaptionInput,
    onBack: () -> Unit,
    onSend: (String?) -> Unit,
) {
    // Not kept in saved state: nothing the user writes is.
    var caption by remember { mutableStateOf("") }
    val key = remember(reference) { KeyBytes("send:${reference.url}".encodeToByteArray()) }
    DisposableEffect(key) {
        tiles.show(key, reference)
        onDispose { tiles.hide(key) }
    }
    val loaded by tiles.tiles.collectAsState()
    val check = rules.check(caption)
    val problem = ConversationTexts.captionProblem(check.verdict)
    val canSend = check.verdict == CaptionVerdict.OK
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth()) { IconTile(ChatTexts.BACK, onBack) { Chevron(it) } }
        // The room the GIF has: whatever the top row and the bar leave (the bar is laid out first, so it is always reachable; on a
        // short window the GIF just gets smaller).  The tile is sized to fit it, whatever the GIF's shape.
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val ratio = tileRatio(loaded[key], reference)
            val width = TileGeometry.fittedWidth(maxWidth.value, maxHeight.value, ratio).dp
            Box(Modifier.width(width).height(width / ratio).clip(MaterialTheme.shapes.large)) {
                GifTileView(loaded[key], reference, "GIF") { tiles.tap(key, reference) }
            }
        }
        CaptionBar(
            caption,
            { caption = it },
            canSend,
            isError = problem != null,
            note = listOfNotNull(problem, ConversationTexts.counter(check.graphemes, CaptionInput.MAX_GRAPHEMES)).joinToString(" · "),
        ) { onSend(check.caption) }
    }
}

/** The caption field in a rounded bar with the round send button; the note (counter, problem) sits above it. */
@Suppress("LongParameterList") // The bar's pieces, handed down from the one place that owns them.
@Composable
private fun CaptionBar(
    caption: String,
    onChange: (String) -> Unit,
    canSend: Boolean,
    isError: Boolean,
    note: String,
    onSend: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            note,
            style = MaterialTheme.typography.labelSmall,
            color = if (isError) colors.error else colors.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        )
        Row(
            Modifier
                .fillMaxWidth()
                .background(
                    colors.surfaceContainerHigh,
                    MaterialTheme.shapes.extraLarge,
                ).padding(start = 6.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextField(
                value = caption,
                onValueChange = onChange,
                placeholder = { Text(ConversationTexts.CAPTION_LABEL) },
                singleLine = true,
                isError = isError,
                colors =
                    TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        errorContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        errorIndicatorColor = Color.Transparent,
                    ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (canSend) onSend() }),
                modifier = Modifier.weight(1f),
            )
            SendButton(ConversationTexts.SEND_GIF, canSend, onSend)
        }
    }
}
