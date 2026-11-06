package app.gifitup.shared.data.content

import app.gifitup.shared.data.crypto.FingerprintEmojis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReactionEmojisTest {
    @Test
    fun theTableHasItsSizeAndNoDuplicates() {
        assertEquals(ReactionEmojis.SIZE, ReactionEmojis.all.size)
        assertEquals(ReactionEmojis.SIZE, ReactionEmojis.all.toSet().size)
        assertTrue(ReactionEmojis.all.none { it.isEmpty() || it.contains(' ') })
    }

    @Test
    fun everyEntryIsItsOwnCanonicalForm() {
        ReactionEmojis.all.forEach { assertEquals(it, ReactionEmojis.canonical(it)) }
        assertNull(ReactionEmojis.canonical(""))
        assertNull(ReactionEmojis.canonical("\u2764"))
    }

    @Test
    fun everyEntryIsOneSymbolPossiblyWithAVariationSelector() {
        ReactionEmojis.all.forEach { entry ->
            val points = entry.codePointsList()
            assertTrue(points.size == 1 || (points.size == 2 && points[1] == 0xFE0F), "entry $points")
        }
    }

    @Test
    fun theFingerprintEmojisAreInsideTheTableSoOneFontCoversBoth() {
        val covered = ReactionEmojis.all.map { it.codePointsList().first() }.toSet()
        FingerprintEmojis.table.forEach { assertTrue(it.emoji.codePointsList().first() in covered, it.name) }
    }

    private fun String.codePointsList(): List<Int> {
        val out = mutableListOf<Int>()
        var i = 0
        while (i < length) {
            val high = this[i]
            if (high.isHighSurrogate() && i + 1 < length) {
                out += 0x10000 + ((high.code - 0xD800) shl 10) + (this[i + 1].code - 0xDC00)
                i += 2
            } else {
                out += high.code
                i++
            }
        }
        return out
    }
}
