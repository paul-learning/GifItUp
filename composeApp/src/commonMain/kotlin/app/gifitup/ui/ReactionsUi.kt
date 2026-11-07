package app.gifitup.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.gifitup.GlyphSupport
import app.gifitup.shared.data.content.ReactionEmojis
import app.gifitup.shared.domain.contacts.KeyBytes
import app.gifitup.shared.domain.messages.DeliveryState
import app.gifitup.shared.domain.messages.MessageBody
import app.gifitup.shared.presentation.chat.ConversationState
import app.gifitup.shared.presentation.chat.ConversationStateHolder
import app.gifitup.shared.presentation.chat.MessageView

/**
 * What the device can draw (ADR-0014), for the screens that show emojis: the reaction picker offers only emojis it knows are
 * drawn. Provided once by [App]; without it nothing is known and no emoji is offered.
 */
internal val LocalGlyphs: ProvidableCompositionLocal<GlyphSupport> = staticCompositionLocalOf { GlyphSupport { null } }

@Composable
internal fun ProvideGlyphs(
    glyphs: GlyphSupport,
    content: @Composable () -> Unit,
) = CompositionLocalProvider(LocalGlyphs provides glyphs, content = content)

/** What a message row needs for reactions: whose reactions are the user's own, whether reacting is possible, how to open the dialog. */
internal class ReactionControls(
    val own: String?,
    val enabled: Boolean,
    val onOpen: (KeyBytes) -> Unit,
)

/** Only a GIF that was sent or received takes reactions: an own message that is still sending or failed has nobody to see it. */
internal fun MessageView.canBeReactedTo(): Boolean = body is MessageBody.Gif && (state == null || state == DeliveryState.SENT)

/** Under a GIF: one chip per emoji with its count (the user's own highlighted), and the button that opens the dialog. */
@Composable
internal fun ReactionBar(
    message: MessageView,
    controls: ReactionControls,
) {
    val glyphs = LocalGlyphs.current
    Row(
        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        message.reactions.groupBy { it.emoji }.forEach { (emoji, reactors) ->
            val mine = controls.own != null && reactors.any { it.by == controls.own }
            Surface(
                onClick = { controls.onOpen(message.reference) },
                shape = MaterialTheme.shapes.extraLarge,
                color = if (mine) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.semantics { contentDescription = ConversationTexts.reactionChip(emoji, reactors.size, mine) },
            ) {
                Text(
                    "$emoji ${reactors.size}",
                    fontFamily = glyphs.emojiFont,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
        TextButton(onClick = { controls.onOpen(message.reference) }, enabled = controls.enabled) { Text(ConversationTexts.REACT) }
    }
}

/** The dialog for the message [target] if it is still in the conversation; a pick goes to the holder and closes it. */
@Composable
internal fun ReactionsDialogFor(
    holder: ConversationStateHolder,
    state: ConversationState,
    target: KeyBytes?,
    onClose: () -> Unit,
) {
    state.messages.firstOrNull { it.reference == target }?.let { message ->
        ReactionsDialog(
            message,
            state.own,
            state.canSend,
            onPick = { emoji ->
                holder.react(message.reference, emoji)
                onClose()
            },
            onDismiss = onClose,
        )
    }
}

/**
 * The reactions of one message: who reacted with what, and the user's own pick. A tap on the emoji the user already has takes
 * it back. It decides nothing: [onPick] gets the emoji (or `null` to take the reaction back) and the holder does the rest.
 */
@Composable
internal fun ReactionsDialog(
    message: MessageView,
    own: String?,
    enabled: Boolean,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val glyphs = LocalGlyphs.current
    // The table's emojis the device is known to draw; on the web this grows when the emoji font has arrived.
    val offered = remember(glyphs.emojiFont) { ReactionEmojis.all.filter { glyphs.supports(it) == true } }
    val quick = remember(offered) { QUICK_REACTIONS.filter { it in offered } }
    val current = own?.let { name -> message.reactions.firstOrNull { it.by == name }?.emoji }
    val choose = { emoji: String -> onPick(if (emoji == current) null else emoji) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(ConversationTexts.REACTIONS_TITLE) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ReactorList(message, glyphs)
                if (enabled) {
                    Text(ConversationTexts.YOUR_REACTION, style = MaterialTheme.typography.titleSmall)
                    if (offered.isEmpty()) Text(ConversationTexts.NO_EMOJI_HERE)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) { quick.forEach { EmojiButton(it, it == current, glyphs, choose) } }
                    LazyVerticalGrid(columns = GridCells.Adaptive(EMOJI_CELL), modifier = Modifier.fillMaxWidth().height(GRID_HEIGHT)) {
                        items(offered) { EmojiButton(it, it == current, glyphs, choose) }
                    }
                }
            }
        },
        confirmButton = {
            if (enabled && current != null) TextButton(onClick = { onPick(null) }) { Text(ConversationTexts.REMOVE_REACTION) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(ConversationTexts.CLOSE) } },
    )
}

@Composable
private fun ReactorList(
    message: MessageView,
    glyphs: GlyphSupport,
) {
    if (message.reactions.isEmpty()) {
        Text(ConversationTexts.NO_REACTIONS)
        return
    }
    // Names come from the MLS credentials of the reactors, isolated like every name on screen; a long list scrolls.
    Column(Modifier.heightIn(max = LIST_HEIGHT).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        message.reactions.forEach {
            Text(ConversationTexts.reactedBy(it.by, it.emoji), fontFamily = glyphs.emojiFont)
        }
    }
}

@Composable
private fun EmojiButton(
    emoji: String,
    selected: Boolean,
    glyphs: GlyphSupport,
    onClick: (String) -> Unit,
) {
    Box(
        Modifier
            .size(EMOJI_CELL)
            .background(
                if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                MaterialTheme.shapes.medium,
            ).semantics { this.selected = selected }
            .clickable(role = Role.Button, onClickLabel = emoji) { onClick(emoji) },
        contentAlignment = Alignment.Center,
    ) {
        Text(emoji, fontSize = EMOJI_SIZE, fontFamily = glyphs.emojiFont)
    }
}

/** The six a person reaches for first; offered on top when the device draws them. */
private val QUICK_REACTIONS =
    listOf("❤️", "👍", "😂", "😮", "😢", "🔥")

private val EMOJI_CELL = 48.dp
private val GRID_HEIGHT = 220.dp
private val LIST_HEIGHT = 120.dp
private val EMOJI_SIZE = 26.sp
