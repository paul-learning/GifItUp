package app.gifitup.shared.domain.messages

private const val DIGITS = "0123456789abcdef"
private const val NIBBLE_BITS = 4
private const val NIBBLE_MASK = 0xF
private const val RADIX = 16

internal fun hex(bytes: ByteArray): String =
    buildString(bytes.size * 2) {
        for (b in bytes) {
            append(DIGITS[(b.toInt() shr NIBBLE_BITS) and NIBBLE_MASK])
            append(DIGITS[b.toInt() and NIBBLE_MASK])
        }
    }

/** The bytes of lower-case hex text, or `null` for anything else. */
internal fun unhex(text: String): ByteArray? =
    if (text.length % 2 != 0 || !text.all { it in DIGITS }) {
        null
    } else {
        ByteArray(text.length / 2) { (DIGITS.indexOf(text[2 * it]) * RADIX + DIGITS.indexOf(text[2 * it + 1])).toByte() }
    }
