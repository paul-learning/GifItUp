package app.gifitup.shared.data.content

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** ADR-0006 B3a: one test vector per rejected range, runs on JVM and wasmJs alike. */
class TextRulesTest {
    private fun cp(codePoint: Int): String =
        if (codePoint < 0x10000) {
            codePoint.toChar().toString()
        } else {
            val offset = codePoint - 0x10000
            charArrayOf((0xD800 + (offset shr 10)).toChar(), (0xDC00 + (offset and 0x3FF)).toChar()).concatToString()
        }

    private fun clean(raw: String) = TextRules.clean(raw, 120)

    @Test
    fun everyRejectedRangeHasAVector() {
        val rejected =
            listOf(
                0x0 to "C0 control",
                0x1F to "C0 control",
                0x7F to "DEL",
                0x80 to "C1 control",
                0x85 to "NEL",
                0x9F to "C1 control",
                0x2028 to "Zl",
                0x2029 to "Zp",
                0xFDD0 to "noncharacter",
                0xFDEF to "noncharacter",
                0xFFFE to "noncharacter",
                0xFFFF to "noncharacter",
                0x1FFFE to "noncharacter",
                0x10FFFF to "noncharacter",
                0xAD to "Cf soft hyphen",
                0x600 to "Cf",
                0x61C to "Cf bidi",
                0x200B to "Cf",
                0x200E to "Cf bidi",
                0x200F to "Cf bidi",
                0x202A to "Cf bidi",
                0x202E to "Cf bidi",
                0x2060 to "Cf",
                0x2066 to "Cf bidi",
                0x2069 to "Cf bidi",
                0x206F to "Cf",
                0xFEFF to "Cf",
                0xFFF9 to "Cf",
                0x110BD to "Cf",
                0xE0001 to "Cf",
                0x13430 to "range",
                0x1343F to "range",
                0x1BCA0 to "range",
                0x1BCA3 to "range",
                0x1D173 to "range",
                0x1D17A to "range",
                0xE0000 to "tag block",
                0xE0020 to "tag block",
                0xE0041 to "tag block",
                0xE007F to "tag block",
            )
        for ((codePoint, what) in rejected) {
            assertNull(clean("a${cp(codePoint)}b"), "U+${codePoint.toString(16)} ($what) in the middle")
            assertNull(clean(cp(codePoint)), "U+${codePoint.toString(16)} ($what) alone")
        }
    }

    @Test
    fun codePointsNextToTheRangesAreFine() {
        for (codePoint in listOf(0x20, 0x7E, 0xA1, 0xAE, 0x2027, 0x202F, 0x206F + 1, 0xFFFD, 0x1F600, 0x1D172, 0x1D17B, 0xE0100, 0xE01EF)) {
            assertEquals("a${cp(codePoint)}b".let(::clean), "a${cp(codePoint)}b", "U+${codePoint.toString(16)}")
        }
    }

    @Test
    fun zeroWidthJoinersAreAllowedBetweenOtherCharacters() {
        val family = "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67"
        assertEquals(family, clean(family))
        assertEquals("\u0645\u06CC\u200C\u062E\u0648\u0627\u0647\u0645", clean("\u0645\u06CC\u200C\u062E\u0648\u0627\u0647\u0645"))
        assertEquals("\u2764\uFE0F", clean("\u2764\uFE0F"))
    }

    @Test
    fun unpairedSurrogatesAreRejected() {
        for (raw in listOf("\uD800", "\uDC00", "a\uD83D", "\uD83Db", "\uDE00\uD83D", "\uD83D\uD83D\uDE00")) {
            assertNull(clean(raw), raw.map { it.code.toString(16) }.toString())
        }
        assertEquals("\uD83D\uDE00", clean("\uD83D\uDE00"))
    }

    @Test
    fun theRawValueIsCheckedBeforeTrimming() {
        assertNull(clean("hi\n"))
        assertNull(clean("\nhi"))
        assertNull(clean("hi\u2028"))
        assertEquals("hi", clean(" hi "))
        assertEquals("hi", clean("\u00A0\u3000 hi\u2003\u202F"))
        assertEquals("a b", clean(" a b "))
    }

    @Test
    fun codePointsAreCountedNotUtf16Units() {
        val emoji = "\uD83D\uDE00"
        assertEquals(emoji.repeat(120), clean(emoji.repeat(120)))
        assertNull(clean(emoji.repeat(121)))
        assertEquals(emoji.repeat(120), clean("  " + emoji.repeat(120) + "  "), "the count is taken after trimming")
        assertEquals("a".repeat(120), clean("a".repeat(120)))
        assertNull(clean("a".repeat(121)))
        assertNull(TextRules.clean("abcde", 4))
        assertEquals("abcd", TextRules.clean("abcd", 4))
    }

    @Test
    fun emptyAndLooksEmptyValues() {
        assertEquals("", clean(""))
        assertEquals("", clean("   "))
        assertEquals("", clean("\u00A0\u3000"))
        for (raw in listOf("\u3164", "\u2800", "\u200D", "\u200C", "\uFE0F", "\u034F", "\u180B", " \u3164 ", "\u2800\u3164\u200D \uFE0F")) {
            assertNull(clean(raw), raw.map { it.code.toString(16) }.toString())
        }
        assertEquals("\u2800a", clean("\u2800a"))
        assertEquals("\u3164 x", clean("\u3164 x"))
    }

    @Test
    fun theTablesAreWellFormed() {
        for ((name, table) in listOf("cf" to FORMAT_CHARACTERS, "ws" to WHITE_SPACE, "di" to DEFAULT_IGNORABLE)) {
            assertEquals(0, table.size % 2, name)
            var previousEnd = -2
            for (index in table.indices step 2) {
                assertTrue(table[index] <= table[index + 1], "$name $index")
                assertTrue(table[index] > previousEnd + 1, "$name sorted and merged at $index")
                assertTrue(table[index + 1] <= 0x10FFFF, name)
                previousEnd = table[index + 1]
            }
        }
    }
}
