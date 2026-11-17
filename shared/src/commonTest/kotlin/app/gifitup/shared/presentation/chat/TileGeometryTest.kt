package app.gifitup.shared.presentation.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TileGeometryTest {
    private fun fits(
        room: Pair<Float, Float>,
        ratio: Float,
    ) {
        val width = TileGeometry.fittedWidth(room.first, room.second, ratio)
        assertTrue(width <= room.first + EPS, "wider than the room")
        assertTrue(width / ratio <= room.second + EPS, "taller than the room")
        assertTrue(width >= 0f)
    }

    @Test
    fun theRatioIsClamped() {
        assertEquals(TileGeometry.MIN_RATIO, TileGeometry.ratio(10, 1000))
        assertEquals(TileGeometry.MAX_RATIO, TileGeometry.ratio(1000, 10))
        assertEquals(1f, TileGeometry.ratio(400, 400))
    }

    @Test
    fun aTallGifIsLimitedByTheHeightOfTheRoom() {
        assertEquals(250f, TileGeometry.fittedWidth(360f, 500f, 0.5f)) // 250 x 500
    }

    @Test
    fun aWideGifIsLimitedByTheWidthOfTheRoom() {
        assertEquals(360f, TileGeometry.fittedWidth(360f, 500f, 2.5f)) // 360 x 144
    }

    @Test
    fun aSquareGifTakesTheShorterSide() {
        assertEquals(360f, TileGeometry.fittedWidth(360f, 500f, 1f))
        assertEquals(200f, TileGeometry.fittedWidth(360f, 200f, 1f))
    }

    @Test
    fun noRoomOrLittleRoomNeverGivesANegativeOrOversizedTile() {
        assertEquals(0f, TileGeometry.fittedWidth(0f, 0f, 1f))
        assertEquals(0f, TileGeometry.fittedWidth(360f, -5f, 1f))
        listOf(0.5f, 1f, 2.5f).forEach { r -> fits(360f to 20f, r) }
        listOf(0.5f, 1f, 2.5f).forEach { r -> fits(20f to 800f, r) }
    }

    private companion object {
        const val EPS = 0.001f
    }
}
