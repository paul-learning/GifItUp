package app.gifitup.ui

import app.gifitup.shared.data.content.GifReference
import app.gifitup.shared.presentation.chat.GifTile
import app.gifitup.shared.presentation.chat.TileGeometry

/**
 * The shape of a GIF's tile (width over height): from the sender's size hint for a placeholder, from the file itself once it is
 * loaded; both clamped by [TileGeometry]. The caption screen fits the tile into the room it has with the same number.
 */
internal fun tileRatio(
    tile: GifTile?,
    reference: GifReference,
): Float =
    when (tile) {
        is GifTile.Ready -> TileGeometry.ratio(tile.info.width, tile.info.height)
        else -> TileGeometry.ratio(reference.width, reference.height)
    }
