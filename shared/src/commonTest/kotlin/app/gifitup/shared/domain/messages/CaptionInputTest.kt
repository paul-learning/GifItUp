package app.gifitup.shared.domain.messages

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** A counter by code points: stands in for the platform's grapheme counter in the tests of the rules around it. */
val CodePointCounter = GraphemeCounter { text -> text.count { !it.isLowSurrogate() } }

class CaptionInputTest {
    private val input = CaptionInput(CodePointCounter)

    @Test
    fun noCaptionIsFineAndStaysNone() {
        for (blank in listOf("", "   ")) {
            val check = input.check(blank)
            assertEquals(CaptionVerdict.OK, check.verdict)
            assertNull(check.caption)
        }
    }

    @Test
    fun aCaptionIsSentTrimmedAndCounted() {
        val check = input.check("  hello 👋 ")
        assertEquals(CaptionVerdict.OK, check.verdict)
        assertEquals("hello 👋", check.caption)
        assertEquals(7, check.graphemes)
    }

    @Test
    fun eightyCharactersFitAndEightyOneDoNot() {
        assertEquals(CaptionVerdict.OK, input.check("a".repeat(80)).verdict)
        val over = input.check("a".repeat(81))
        assertEquals(CaptionVerdict.TOO_LONG, over.verdict)
        assertEquals(81, over.graphemes)
        assertNull(over.caption)
    }

    @Test
    fun theCodecCapOfCodePointsAppliesEvenIfTheCounterSaysFewCharacters() {
        // A counter that sees one character in anything: only the codec's 120 code points stop it.
        val lenient = CaptionInput { 1 }
        assertEquals(CaptionVerdict.OK, lenient.check("a".repeat(120)).verdict)
        assertEquals(CaptionVerdict.TOO_LONG, lenient.check("a".repeat(121)).verdict)
    }

    @Test
    fun textThatBreaksTheRulesIsInvalidNotTooLong() {
        assertEquals(CaptionVerdict.INVALID, input.check("one\ntwo").verdict)
        assertEquals(CaptionVerdict.INVALID, input.check("a".repeat(200) + "\u0000").verdict)
        assertEquals(CaptionVerdict.INVALID, input.check("\uD800").verdict)
        assertEquals(CaptionVerdict.INVALID, input.check("​​").verdict)
    }
}
