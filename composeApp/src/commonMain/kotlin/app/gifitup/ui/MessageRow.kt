package app.gifitup.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
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
)

/** One message: a GIF bubble with its caption on it, and under it who sent it and when; or a plain line for a rename. */
@Composable
internal fun MessageRow(
    message: MessageView,
    tiles: GifTilesStateHolder,
    tile: GifTile?,
) {
    val mine = message.sender == null
    val sender = message.sender?.let(ChatTexts::isolated) ?: ConversationTexts.YOU
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        when (val body = message.body) {
            is MessageBody.Gif -> GifBubble(message, body, GifMedia(tiles, tile), sender, mine)
            is MessageBody.GroupMeta -> Text(ConversationTexts.renamed(body.name), style = MaterialTheme.typography.bodySmall)
            MessageBody.Unavailable -> Text(ConversationTexts.UNAVAILABLE, style = MaterialTheme.typography.bodySmall)
            MessageBody.UpdateNeeded -> Text(ConversationTexts.UPDATE_NEEDED, style = MaterialTheme.typography.bodySmall)
        }
        SenderLine(message, sender, mine)
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
    val (tiles, tile) = media
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
            Box {
                GifTileView(
                    tile,
                    body.reference,
                    ConversationTexts.gifDescription(sender, body.caption),
                ) { tiles.tap(message.reference, body.reference) }
                GifChip(Modifier.align(Alignment.TopStart).padding(12.dp))
                body.caption?.let { CaptionOnGif(it, Modifier.align(Alignment.BottomStart)) }
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

/** The caption over the lower edge of the GIF, on a dark fade so it reads on any picture; plain text, one line. */
@Composable
private fun CaptionOnGif(
    caption: String,
    modifier: Modifier,
) {
    Box(
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = CAPTION_SCRIM))))
            .padding(start = 12.dp, end = 12.dp, top = 20.dp, bottom = 12.dp),
    ) {
        // One line, clipped to a bounded height: a stack of combining marks can't grow the bubble.
        Text(
            ChatTexts.isolated(caption),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.heightIn(max = CAPTION_MAX_HEIGHT).clipToBounds(),
        )
    }
}

/** The GIF itself, or a placeholder of the same shape that says what is going on. */
@Composable
internal fun GifTileView(
    tile: GifTile?,
    reference: GifReference,
    description: String,
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
            AnimatedGif(tile.data, tile.info, description, shape) { failed = true }
        }

        tile is GifTile.TapToLoad -> {
            Placeholder(
                shape,
                description,
                PlaceholderWords(ConversationTexts.TAP_TO_LOAD, ConversationTexts.TAP_TO_LOAD_WHY, ConversationTexts.LOAD_GIF),
                onTap,
            )
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
            )
        }

        failed -> {
            Placeholder(shape, description, PlaceholderWords(ConversationTexts.GIF_CANT_SHOW, null, null), null)
        }

        else -> {
            Box(shape.semantics { contentDescription = ConversationTexts.LOADING_GIF }, contentAlignment = Alignment.Center) {
                CircularProgressIndicator(strokeWidth = 2.dp)
            }
        }
    }
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
) {
    val (title, hint, tapLabel) = words
    val tap = if (onTap != null) Modifier.clickable(onClickLabel = tapLabel, role = Role.Button, onClick = onTap) else Modifier
    Box(
        modifier.then(tap).padding(8.dp).semantics(mergeDescendants = true) {
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
private const val CAPTION_SCRIM = 0.65f
private val SENDER_AVATAR_SIZE = 24.dp
private val BUBBLE_MAX_WIDTH = 320.dp
private val CAPTION_MAX_HEIGHT = 28.dp
private const val MIN_RATIO = 0.5f
private const val MAX_RATIO = 2.5f
