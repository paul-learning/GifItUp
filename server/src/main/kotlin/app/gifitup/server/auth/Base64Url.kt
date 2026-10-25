package app.gifitup.server.auth

import java.util.Base64

/**
 * Strict base64url without padding for the byte fields of the auth messages (ADR-0009). One spelling per value: the raw
 * string's length and alphabet are checked before decoding, and the decoded bytes must re-encode to the same string,
 * so non-canonical trailing bits are refused too. Returns null on any violation, never throws.
 */
internal object Base64Url {
    private val ALPHABET = Regex("^[A-Za-z0-9_-]*$")
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    fun encode(bytes: ByteArray): String = encoder.encodeToString(bytes)

    fun decode(
        text: String,
        size: Int,
    ): ByteArray? {
        val bytes =
            text
                .takeIf { it.length == encodedLength(size) && ALPHABET.matches(it) }
                ?.let { runCatching { decoder.decode(it) }.getOrNull() }
        return bytes?.takeIf { it.size == size && encode(it) == text }
    }

    /**
     * The same strictness for a value of any length from 1 to [maxBytes]: alphabet and length are checked before
     * decoding (so an oversized string is never decoded), and the bytes must re-encode to the same text, so padding and
     * non-zero trailing bits are refused. Null on any violation.
     */
    fun decodeUpTo(
        text: String,
        maxBytes: Int,
    ): ByteArray? {
        val bytes =
            text
                .takeIf { it.isNotEmpty() && it.length <= encodedLength(maxBytes) && ALPHABET.matches(it) }
                ?.let { runCatching { decoder.decode(it) }.getOrNull() }
        return bytes?.takeIf { it.isNotEmpty() && it.size <= maxBytes && encode(it) == text }
    }

    /** The length of the unpadded base64url text of [size] bytes. */
    fun encodedLength(size: Int): Int = (size * BITS_PER_BYTE + BITS_PER_CHAR - 1) / BITS_PER_CHAR

    private const val BITS_PER_BYTE = 8
    private const val BITS_PER_CHAR = 6
}
