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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
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

/** The long-press action of a message's GIF: opens its reactions, or `null` where reacting is not possible right now. */
internal fun ReactionControls.holdAction(message: MessageView): (() -> Unit)? =
    if (enabled && message.canBeReactedTo) ({ onOpen(message.reference) }) else null

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
        // On Android reacting is the long tap only (owner, #154); the web keeps the button, where a long press is not a habit.
        if (SHOW_REACT_BUTTON) {
            TextButton(onClick = {
                controls.onOpen(message.reference)
            }, enabled = controls.enabled) { Text(ConversationTexts.REACT) }
        }
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
 * The reactions of one message, in the style of Signal: a pill with the six quick emojis (the user's own highlighted) and a
 * "+" that opens the full set, and under it who reacted with what. A tap on the emoji the user already has takes it back.
 * Tapping outside or going back closes it. It decides nothing: [onPick] gets the emoji (or `null` to take the reaction back)
 * and the holder does the rest.
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
    var expanded by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (enabled) {
                Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Column(Modifier.padding(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            quick.forEach { EmojiButton(it, it == current, glyphs, choose) }
                            MoreButton(expanded) { expanded = !expanded }
                        }
                        if (expanded) {
                            if (offered.isEmpty()) Text(ConversationTexts.NO_EMOJI_HERE, Modifier.padding(12.dp))
                            LazyVerticalGrid(
                                columns = GridCells.Adaptive(EMOJI_CELL),
                                modifier = Modifier.fillMaxWidth().height(GRID_HEIGHT),
                            ) { items(offered) { EmojiButton(it, it == current, glyphs, choose) } }
                        }
                    }
                }
            }
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    ReactorList(message, glyphs)
                    // Whatever the device can draw: a reaction it can't show in the row can still be taken back.
                    if (enabled && current != null) TextButton(onClick = { onPick(null) }) { Text(ConversationTexts.REMOVE_REACTION) }
                }
            }
        }
    }
}

/** The "+" at the end of the quick row: shows or hides the full set of emojis. */
@Composable
private fun MoreButton(
    expanded: Boolean,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(EMOJI_CELL)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainer, CircleShape)
            .clickable(role = Role.Button, onClickLabel = ConversationTexts.MORE_EMOJIS, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(if (expanded) "−" else "+", style = MaterialTheme.typography.titleLarge) }
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
            .clip(CircleShape)
            .background(
                if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                CircleShape,
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

private val EMOJI_CELL = 44.dp
private val GRID_HEIGHT = 220.dp
private val LIST_HEIGHT = 120.dp
private val EMOJI_SIZE = 26.sp
