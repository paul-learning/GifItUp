package app.gifitup.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.gifitup.shared.data.content.GifReference
import app.gifitup.shared.domain.contacts.KeyBytes
import app.gifitup.shared.domain.messages.CaptionInput
import app.gifitup.shared.domain.messages.CaptionVerdict
import app.gifitup.shared.presentation.chat.GifTilesStateHolder

/**
 * The step after picking (#217): the chosen GIF shown large, an optional caption under it, and Send. "Back" returns to
 * the results. Renders state and decides nothing (ADR-0013): the caption rules are [CaptionInput]'s.
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
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AppTopBar(title = ConversationTexts.SEND_TITLE, onBack = onBack)
        GifTileView(loaded[key], reference, "GIF") { tiles.tap(key, reference) }
        OutlinedTextField(
            value = caption,
            onValueChange = { caption = it },
            label = { Text(ConversationTexts.CAPTION_LABEL) },
            singleLine = true,
            isError = problem != null,
            supportingText = {
                Text(
                    listOfNotNull(problem, ConversationTexts.counter(check.graphemes, CaptionInput.MAX_GRAPHEMES)).joinToString(" · "),
                )
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = { onSend(check.caption) }, enabled = check.verdict == CaptionVerdict.OK, modifier = Modifier.fillMaxWidth()) {
            Text(ConversationTexts.SEND_GIF)
        }
    }
}
