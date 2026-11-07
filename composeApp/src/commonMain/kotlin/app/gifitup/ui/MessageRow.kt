package app.gifitup.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.gifitup.shared.data.content.GifReference
import app.gifitup.shared.domain.messages.DeliveryState
import app.gifitup.shared.domain.messages.MessageBody
import app.gifitup.shared.presentation.chat.GifTile
import app.gifitup.shared.presentation.chat.GifTilesStateHolder
import app.gifitup.shared.presentation.chat.MessageView

/** The tile holder and this bubble's tile in it. */
private data class GifMedia(
    val tiles: GifTilesStateHolder,
    val tile: GifTile?,
    /** Opens the message's reactions on a long press, or `null` where reacting is not possible right now. */
    val onReact: (() -> Unit)? = null,
)

/** One message: a GIF bubble with its caption on it, and under it who sent it and when; or a plain line for a rename. */
@Composable
internal fun MessageRow(
    message: MessageView,
    tiles: GifTilesStateHolder,
    tile: GifTile?,
    reactions: ReactionControls,
) {
    val mine = message.sender == null
    val sender = message.sender?.let(ChatTexts::isolated) ?: ConversationTexts.YOU
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        when (val body = message.body) {
            is MessageBody.Gif -> GifBubble(message, body, GifMedia(tiles, tile, reactions.holdAction(message)), sender, mine)
            is MessageBody.GroupMeta -> Text(ConversationTexts.renamed(body.name), style = MaterialTheme.typography.bodySmall)
            MessageBody.Unavailable -> Text(ConversationTexts.UNAVAILABLE, style = MaterialTheme.typography.bodySmall)
            MessageBody.UpdateNeeded -> Text(ConversationTexts.UPDATE_NEEDED, style = MaterialTheme.typography.bodySmall)
            is MessageBody.Membership -> Text(ConversationTexts.membership(body), style = MaterialTheme.typography.bodySmall)
        }
        if (message.canBeReactedTo) ReactionBar(message, reactions)
        if (message.body !is MessageBody.Membership) SenderLine(message, sender, mine)
    }
}

/** Under a bubble: the sender with an avatar (theirs) or "you" (ours), the time, and where one of ours stands. */
@Composable
private fun SenderLine(
    message: MessageView,
    sender: String,
    mine: Boolean,
) {
    val colors = MaterialTheme.colorScheme
    val time = formatClockTime(message.at)
    val state =
        when (message.state) {
            DeliveryState.SENDING -> " · ${ConversationTexts.SENDING}"
            DeliveryState.FAILED -> " · ${ConversationTexts.NOT_SENT}"
            DeliveryState.SENT -> " · ${ConversationTexts.SENT}"
            null -> ""
        }
    Row(
        Modifier.padding(horizontal = 4.dp).semantics(mergeDescendants = true) { contentDescription = "$sender, $time$state" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (!mine) Avatar(message.sender.orEmpty(), SENDER_AVATAR_SIZE)
        Text(
            sender,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = if (mine) colors.secondary else colors.onSurface,
        )
        Text(
            time + state,
            style = MaterialTheme.typography.labelMedium,
            color = if (message.state == DeliveryState.FAILED) colors.error else colors.onSurfaceVariant,
        )
    }
}

@Composable
private fun GifBubble(
    message: MessageView,
    body: MessageBody.Gif,
    media: GifMedia,
    sender: String,
    mine: Boolean,
) {
    val (tiles, tile, onReact) = media
    // The bubble asks for its media while it is composed and gives it back when it leaves the screen.
    DisposableEffect(message.reference) {
        tiles.show(message.reference, body.reference)
        onDispose { tiles.hide(message.reference) }
    }
    // Four fifths of the width, at most BUBBLE_MAX_WIDTH, on the side of the sender.
    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
        Surface(
            color = if (mine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.width(minOf(maxWidth * BUBBLE_FRACTION, BUBBLE_MAX_WIDTH)),
        ) {
            Column {
                Box {
                    GifTileView(
                        tile,
                        body.reference,
                        ConversationTexts.gifDescription(sender, body.caption),
                        onLongPress = onReact,
                    ) { tiles.tap(message.reference, body.reference) }
                    GifChip(Modifier.align(Alignment.TopStart).padding(12.dp))
                }
                body.caption?.let { CaptionUnderGif(it, mine) }
            }
        }
    }
}

/** The small "GIF" label on a bubble; decorative, the description of the bubble says what it is. */
@Composable
private fun GifChip(modifier: Modifier) {
    Text(
        ConversationTexts.GIF_CHIP,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = Color.White,
        modifier =
            modifier
                .background(Color.Black.copy(alpha = CHIP_SCRIM), MaterialTheme.shapes.small)
                .padding(horizontal = 8.dp, vertical = 2.dp)
                .clearAndSetSemantics { },
    )
}

/**
 * The caption under the GIF, inside the bubble: its own text on the bubble's colour, so it reads on any picture and a
 * long one wraps instead of being cut off. Plain text; the height is bounded, so a stack of combining marks can't grow the
 * bubble.
 */
@Composable
private fun CaptionUnderGif(
    caption: String,
    mine: Boolean,
) {
    Text(
        ChatTexts.isolated(caption),
        style = MaterialTheme.typography.bodyLarge,
        color = if (mine) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        maxLines = CAPTION_MAX_LINES,
        overflow = TextOverflow.Ellipsis,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .heightIn(max = CAPTION_MAX_HEIGHT)
                .clipToBounds(),
    )
}

/**
 * A tap and, when [onLongPress] is given, a long press that opens the reactions (ADR-0006 B10a). The long press has its own
 * label for a screen reader and a short vibration on a touch screen. A target with no tap is not announced as a button: it
 * only offers the long-press action. Without either action the modifier does nothing.
 */
@Composable
private fun Modifier.pressable(
    tapLabel: String?,
    onTap: (() -> Unit)?,
    onLongPress: (() -> Unit)?,
): Modifier {
    if (onTap == null && onLongPress == null) return this
    val haptics = LocalHapticFeedback.current
    val hold: (() -> Unit)? =
        onLongPress?.let { open ->
            {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                open()
            }
        }
    return if (onTap != null) {
        combinedClickable(
            onClickLabel = tapLabel,
            role = Role.Button,
            onLongClickLabel = hold?.let { ConversationTexts.REACT_TO_GIF },
            onLongClick = hold,
            onClick = onTap,
        )
    } else {
        // Only a long press: no click semantics, so nothing is announced as a button that does nothing.
        pointerInput(hold) { detectTapGestures(onLongPress = { hold?.invoke() }) }
            .semantics {
                onLongClick(ConversationTexts.REACT_TO_GIF) {
                    hold?.invoke()
                    true
                }
            }
    }
}

/** The GIF itself, or a placeholder of the same shape that says what is going on. */
@Suppress("LongParameterList") // The tile, what it shows, and its two optional actions; three screens call it with defaults.
@Composable
internal fun GifTileView(
    tile: GifTile?,
    reference: GifReference,
    description: String,
    pausable: Boolean = true,
    onLongPress: (() -> Unit)? = null,
    onTap: () -> Unit,
) {
    // The shape of a placeholder comes from the sender's size hint; once loaded, from the file itself. Both are clamped.
    val ratio =
        when (tile) {
            is GifTile.Ready -> tile.info.width.toFloat() / tile.info.height
            else -> reference.width.toFloat() / reference.height
        }.coerceIn(MIN_RATIO, MAX_RATIO)
    var failed by remember(tile) { mutableStateOf(false) }
    val shape = Modifier.fillMaxWidth().aspectRatio(ratio)
    when {
        tile is GifTile.Ready && !failed -> {
            PausableGif(tile, description, shape, pausable, onLongPress) { failed = true }
        }

        tile is GifTile.Unavailable -> {
            Placeholder(
                shape,
                description,
                PlaceholderWords(
                    ConversationTexts.gifUnavailable(tile.reason),
                    ConversationTexts.GIF_FAILED_RETRY,
                    ConversationTexts.RETRY_GIF,
                ),
                onTap,
                onLongPress,
            )
        }

        failed -> {
            Placeholder(shape, description, PlaceholderWords(ConversationTexts.GIF_CANT_SHOW, null, null), null, onLongPress)
        }

        else -> {
            Box(
                shape.pressable(null, null, onLongPress).semantics { contentDescription = ConversationTexts.LOADING_GIF },
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(strokeWidth = 2.dp)
            }
        }
    }
}

/**
 * A loaded GIF: a tap pauses it (the frame stays, with a "Paused" label) and a tap plays it again. Not [pausable] where the
 * tap means something else (the picker: a tap chooses the GIF). The state is the tile's: it starts again when the bubble
 * leaves the screen and comes back.
 */
@Suppress("LongParameterList") // The tile's pieces, handed down from GifTileView.
@Composable
private fun PausableGif(
    tile: GifTile.Ready,
    description: String,
    shape: Modifier,
    pausable: Boolean,
    onLongPress: (() -> Unit)?,
    onFailed: () -> Unit,
) {
    var paused by remember(tile) { mutableStateOf(false) }
    // With less motion asked for the GIF is a still picture already: there is nothing to pause.
    val canPause = pausable && !prefersReducedMotion()
    val label = if (paused) ConversationTexts.PLAY_GIF else ConversationTexts.PAUSE_GIF
    val tap = shape.pressable(if (canPause) label else null, if (canPause) ({ paused = !paused }) else null, onLongPress)
    Box(tap, contentAlignment = Alignment.Center) {
        AnimatedGif(tile.data, tile.info, description, Modifier.fillMaxSize(), paused, onFailed)
        if (paused) PausedChip()
    }
}

/** The "Paused" label in the middle of a paused GIF; the click label of the GIF already says how to play it. */
@Composable
private fun PausedChip() {
    Text(
        ConversationTexts.PAUSED,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        color = Color.White,
        modifier =
            Modifier
                .background(Color.Black.copy(alpha = CHIP_SCRIM), MaterialTheme.shapes.small)
                .padding(horizontal = 12.dp, vertical = 4.dp)
                .clearAndSetSemantics { },
    )
}

/** What a placeholder says: [title], an optional [hint] under it, and the label of its tap action for a screen reader. */
private data class PlaceholderWords(
    val title: String,
    val hint: String?,
    val tapLabel: String?,
)

@Composable
private fun Placeholder(
    modifier: Modifier,
    description: String,
    words: PlaceholderWords,
    onTap: (() -> Unit)?,
    onLongPress: (() -> Unit)?,
) {
    val (title, hint, tapLabel) = words
    Box(
        modifier.pressable(tapLabel, onTap, onLongPress).padding(8.dp).semantics(mergeDescendants = true) {
            contentDescription =
                listOfNotNull(description, title, hint).joinToString(". ")
        },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            hint?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

private const val BUBBLE_FRACTION = 0.8f
private const val CHIP_SCRIM = 0.45f
private const val CAPTION_MAX_LINES = 6
private val SENDER_AVATAR_SIZE = 24.dp
private val BUBBLE_MAX_WIDTH = 320.dp
private val CAPTION_MAX_HEIGHT = 160.dp
private const val MIN_RATIO = 0.5f
private const val MAX_RATIO = 2.5f
