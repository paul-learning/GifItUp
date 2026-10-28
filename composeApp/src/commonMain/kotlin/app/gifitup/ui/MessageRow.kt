package app.gifitup.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.gifitup.shared.data.content.GifReference
import app.gifitup.shared.domain.messages.DeliveryState
import app.gifitup.shared.domain.messages.MessageBody
import app.gifitup.shared.presentation.chat.Ago
import app.gifitup.shared.presentation.chat.GifTile
import app.gifitup.shared.presentation.chat.GifTilesStateHolder
import app.gifitup.shared.presentation.chat.MessageView
import kotlin.time.Clock

/** The tile holder and this bubble's tile in it. */
private data class GifMedia(
    val tiles: GifTilesStateHolder,
    val tile: GifTile?,
)

/** One message: a GIF bubble with its caption below, sender and time, or a plain line for a rename or an unreadable one. */
@Composable
internal fun MessageRow(
    message: MessageView,
    tiles: GifTilesStateHolder,
    tile: GifTile?,
    group: Boolean,
) {
    val mine = message.sender == null
    val sender = message.sender?.let(ChatTexts::isolated) ?: ConversationTexts.YOU
    val ago = ChatTexts.ago(Ago.of(Clock.System.now().epochSeconds, message.at))
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        when (val body = message.body) {
            is MessageBody.Gif -> GifBubble(message, body, GifMedia(tiles, tile), sender, mine)
            is MessageBody.GroupMeta -> Text(ConversationTexts.renamed(body.name), style = MaterialTheme.typography.bodySmall)
            MessageBody.Unavailable -> Text(ConversationTexts.UNAVAILABLE, style = MaterialTheme.typography.bodySmall)
            MessageBody.UpdateNeeded -> Text(ConversationTexts.UPDATE_NEEDED, style = MaterialTheme.typography.bodySmall)
        }
        val state =
            when (message.state) {
                DeliveryState.SENDING -> " · ${ConversationTexts.SENDING}"
                DeliveryState.FAILED -> " · ${ConversationTexts.NOT_SENT}"
                else -> ""
            }
        Text(
            (if (group || mine) "$sender · " else "") + ago + state,
            style = MaterialTheme.typography.labelSmall,
            color =
                if (message.state ==
                    DeliveryState.FAILED
                ) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
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
    Surface(
        color = if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(BUBBLE_FRACTION).widthIn(max = BUBBLE_MAX_WIDTH),
    ) {
        Column(modifier = Modifier.padding(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            GifTileView(
                tile,
                body.reference,
                ConversationTexts.gifDescription(sender, body.caption),
            ) { tiles.tap(message.reference, body.reference) }
            body.caption?.let {
                // One line, clipped to a bounded height: a stack of combining marks can't grow the bubble.
                Text(
                    ChatTexts.isolated(it),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.heightIn(max = CAPTION_MAX_HEIGHT).clipToBounds(),
                )
            }
        }
    }
}

/** The GIF itself, or a placeholder of the same shape that says what is going on. */
@Composable
private fun GifTileView(
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
            Placeholder(shape, description, ConversationTexts.TAP_TO_LOAD, ConversationTexts.TAP_TO_LOAD_WHY, onTap)
        }

        tile is GifTile.Unavailable -> {
            Placeholder(shape, description, ConversationTexts.gifUnavailable(tile.reason), ConversationTexts.GIF_FAILED_RETRY, onTap)
        }

        failed -> {
            Placeholder(shape, description, ConversationTexts.GIF_CANT_SHOW, null, null)
        }

        else -> {
            Box(shape.semantics { contentDescription = ConversationTexts.LOADING_GIF }, contentAlignment = Alignment.Center) {
                CircularProgressIndicator(strokeWidth = 2.dp)
            }
        }
    }
}

@Composable
private fun Placeholder(
    modifier: Modifier,
    description: String,
    title: String,
    hint: String?,
    onTap: (() -> Unit)?,
) {
    val tap = if (onTap != null) Modifier.clickable(onClick = onTap) else Modifier
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

private const val BUBBLE_FRACTION = 0.75f
private val BUBBLE_MAX_WIDTH = 320.dp
private val CAPTION_MAX_HEIGHT = 28.dp
private const val MIN_RATIO = 0.5f
private const val MAX_RATIO = 2.5f
