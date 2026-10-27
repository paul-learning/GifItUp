package app.gifitup.shared.data.content

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A cross-check of the generated tables against the JDK's own (older) Unicode data: whatever the JDK knows as Cf or
 * White_Space-like must agree with the tables, so a broken generator or a wrong file shows up. The tables are the rule,
 * not the JDK: the JDK's version differs from the pinned one and from other platforms (ADR-0006 B3a).
 */
class UnicodeTablesJvmTest {
    private fun inTable(
        table: IntArray,
        codePoint: Int,
    ): Boolean = (table.indices step 2).any { codePoint in table[it]..table[it + 1] }

    @Test
    fun everyFormatCharacterTheJdkKnowsIsInTheTable() {
        for (codePoint in 0..Character.MAX_CODE_POINT) {
            if (Character.getType(codePoint) == Character.FORMAT.toInt()) {
                assertTrue(inTable(FORMAT_CHARACTERS, codePoint), "U+${codePoint.toString(16)}")
            }
        }
    }

    @Test
    fun everyCodePointInTheFormatTableIsFormatOrNewerThanTheJdk() {
        for (index in FORMAT_CHARACTERS.indices step 2) {
            for (codePoint in FORMAT_CHARACTERS[index]..FORMAT_CHARACTERS[index + 1]) {
                val type = Character.getType(codePoint)
                assertTrue(
                    type == Character.FORMAT.toInt() || type == Character.UNASSIGNED.toInt(),
                    "U+${codePoint.toString(16)} is type $type in the JDK",
                )
            }
        }
    }

    @Test
    fun theJdksSpacesAreWhiteSpace() {
        for (codePoint in 0..Character.MAX_CODE_POINT) {
            val space =
                Character.getType(codePoint).let {
                    it == Character.SPACE_SEPARATOR.toInt() || it == Character.LINE_SEPARATOR.toInt() ||
                        it == Character.PARAGRAPH_SEPARATOR.toInt()
                }
            if (space) assertTrue(inTable(WHITE_SPACE, codePoint), "U+${codePoint.toString(16)}")
        }
        assertTrue(inTable(WHITE_SPACE, 0x85) && inTable(WHITE_SPACE, 0x09) && inTable(WHITE_SPACE, 0xA0))
    }
}
