package app.gifitup.shared.data.content

/**
 * The text check for sender-supplied strings (`gif.caption`, `group_meta.name`), ADR-0006 B3a. It works on Unicode code
 * points, never on UTF-16 units, and uses the fixed tables of [UnicodeTables.kt] and not the platform's character tables:
 * JVM, Android and Kotlin/Wasm ship different Unicode versions, and the same text must be valid on all of them or none.
 *
 * Order of the checks: (1) every code point of the raw value against the rejected set, so a newline anywhere makes the
 * value invalid; (2) leading and trailing White_Space is trimmed; (3) the code points are counted; (4) a value that is
 * not empty after trimming must contain something visible.
 */
@Suppress("MagicNumber") // Unicode code points and ranges are the content of this file.
object TextRules {
    private const val ZWNJ = 0x200C
    private const val ZWJ = 0x200D
    private const val BRAILLE_BLANK = 0x2800

    /** The explicit ranges of B3a (sorted, like the tables): parts of them are unassigned, so a category lookup would miss them. */
    private val explicitRanges =
        intArrayOf(0x13430, 0x1343F, 0x1BCA0, 0x1BCA3, 0x1D173, 0x1D17A, 0xE0000, 0xE007F)

    /**
     * The trimmed [raw], possibly empty, or `null` if it breaks the rules or has more than [maxCodePoints] code points
     * after trimming. The caller decides what an empty result means (no caption, or an invalid group name).
     */
    fun clean(
        raw: String,
        maxCodePoints: Int,
    ): String? {
        val text = CodePoints.of(raw)?.takeIf { cp -> (0 until cp.size).none { isRejected(cp.points[it]) } } ?: return null
        var first = 0
        var end = text.size
        while (first < end && inTable(WHITE_SPACE, text.points[first])) first++
        while (end > first && inTable(WHITE_SPACE, text.points[end - 1])) end--
        val visible = (first until end).any { !isBlank(text.points[it]) }
        val acceptable = end - first <= maxCodePoints && (first == end || visible)
        return if (acceptable) raw.substring(text.offsets[first], text.offsets[end]) else null
    }

    private fun isBlank(codePoint: Int): Boolean =
        inTable(WHITE_SPACE, codePoint) || inTable(DEFAULT_IGNORABLE, codePoint) || codePoint == BRAILLE_BLANK

    private fun isRejected(codePoint: Int): Boolean =
        codePoint <= 0x1F ||
            codePoint in 0x7F..0x9F ||
            codePoint == 0x2028 ||
            codePoint == 0x2029 ||
            codePoint in 0xFDD0..0xFDEF ||
            (codePoint and 0xFFFE) == 0xFFFE ||
            (codePoint != ZWNJ && codePoint != ZWJ && inTable(FORMAT_CHARACTERS, codePoint)) ||
            inTable(explicitRanges, codePoint)

    /** A table is sorted pairs of first and last code point. */
    private fun inTable(
        table: IntArray,
        codePoint: Int,
    ): Boolean {
        var low = 0
        var high = table.size / 2 - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            when {
                codePoint < table[2 * mid] -> high = mid - 1
                codePoint > table[2 * mid + 1] -> low = mid + 1
                else -> return true
            }
        }
        return false
    }

    /** The code points of a string and where each starts in it ([offsets] has one more entry: the length). */
    private class CodePoints(
        val points: IntArray,
        val offsets: IntArray,
        val size: Int,
    ) {
        companion object {
            /** `null` if the string has an unpaired surrogate. */
            fun of(text: String): CodePoints? {
                val points = IntArray(text.length)
                val offsets = IntArray(text.length + 1)
                var count = 0
                var index = 0
                while (index < text.length) {
                    val unit = text[index]
                    val next = text.getOrNull(index + 1)
                    val width =
                        when {
                            unit.isHighSurrogate() && next != null && next.isLowSurrogate() -> 2
                            unit.isSurrogate() -> return null
                            else -> 1
                        }
                    points[count] = if (width == 2) toCodePoint(unit, next!!) else unit.code
                    offsets[count] = index
                    count++
                    index += width
                }
                offsets[count] = text.length
                return CodePoints(points, offsets, count)
            }

            private const val SUPPLEMENTARY = 0x10000
            private const val TEN_BITS = 10

            private fun toCodePoint(
                high: Char,
                low: Char,
            ): Int = SUPPLEMENTARY + ((high.code - 0xD800) shl TEN_BITS) + (low.code - 0xDC00)
        }
    }
}
