package app.gifitup.shared.presentation.chat

/**
 * The shape rules of a GIF's tile, as plain numbers so the screens only draw them and the rules are tested (#154, the caption
 * screen): the width-over-height ratio is clamped, and a tile that must fit a room is as wide as the room allows without being
 * taller than it.
 */
object TileGeometry {
    const val MIN_RATIO = 0.5f
    const val MAX_RATIO = 2.5f

    /** Width over height of a [width] by [height] picture (sizes of at least 1, as the reference and the scanner guarantee), clamped. */
    fun ratio(
        width: Int,
        height: Int,
    ): Float = (width.toFloat() / height).coerceIn(MIN_RATIO, MAX_RATIO)

    /** The width of a tile of [ratio] that fits a room of [maxWidth] by [maxHeight]: never wider or taller than it, never negative. */
    fun fittedWidth(
        maxWidth: Float,
        maxHeight: Float,
        ratio: Float,
    ): Float = minOf(maxWidth, maxHeight * ratio).coerceAtLeast(0f)
}
