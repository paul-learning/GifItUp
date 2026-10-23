package app.gifitup.shared.data.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The table's checkable rules (ADR-0014). The file comparison with the crate's list is a JVM test. */
class FingerprintEmojisTest {
    private val table = FingerprintEmojis.table

    @Test
    fun hasExactly256UniqueEntries() {
        assertEquals(FingerprintEmojis.TABLE_SIZE, table.size)
        assertEquals(256, table.map { it.emoji }.toSet().size, "unique emojis")
        assertEquals(256, table.map { it.name }.toSet().size, "unique names")
    }

    @Test
    fun namesAreReadableAndTheirFirstWordsDiffer() {
        assertTrue(table.all { it.name.isNotBlank() && it.name == it.name.trim() && it.name.length <= 24 })
        val firstWords = table.map { it.name.split(' ', '-').first() }
        assertEquals(
            firstWords.size,
            firstWords.toSet().size,
            "first words: ${firstWords.groupBy { it }.filterValues { it.size > 1 }.keys}",
        )
    }

    @Test
    fun everyEntryIsOneCodePointWithoutSelectorsOrJoiners() {
        table.forEach { cell ->
            val e = cell.emoji
            val oneCodePoint = e.length == 1 || (e.length == 2 && e[0].isHighSurrogate() && e[1].isLowSurrogate())
            assertTrue(oneCodePoint, "${cell.name} is one code point")
            assertTrue(e.none { it == '\\uFE0F' || it == '\\uFE0E' || it == '\\u200D' }, "${cell.name}: no variation selector or joiner")
        }
    }

    @Test
    fun indicesAreReadUnsigned() {
        val indices = byteArrayOf(0, 127, -128, -1, 1, 2, 3, 4, 5, 6, 7, 8)
        val cells = FingerprintEmojis.cells(indices)
        assertEquals(table[0], cells[0])
        assertEquals(table[127], cells[1])
        assertEquals(table[128], cells[2], "a byte of 0x80 is index 128, not -128")
        assertEquals(table[255], cells[3])
    }

    @Test
    fun onlyTwelveIndicesAreAFingerprint() {
        listOf(0, 1, 11, 13, 32).forEach { size ->
            assertFailsWith<IllegalArgumentException> { FingerprintEmojis.cells(ByteArray(size)) }
        }
    }
}
