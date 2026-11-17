package app.gifitup.ui

import app.gifitup.shared.data.content.GifReference
import app.gifitup.shared.presentation.chat.GifTile

/**
 * The shape of a GIF's tile (width over height): from the sender's size hint for a placeholder, from the file itself once it is
 * loaded; both clamped. The caption screen fits the tile into the room it has with the same number.
 */
internal fun tileRatio(
    tile: GifTile?,
    reference: GifReference,
): Float =
    when (tile) {
        is GifTile.Ready -> tile.info.width.toFloat() / tile.info.height
        else -> reference.width.toFloat() / reference.height
    }.coerceIn(MIN_RATIO, MAX_RATIO)

private const val MIN_RATIO = 0.5f
private const val MAX_RATIO = 2.5f
