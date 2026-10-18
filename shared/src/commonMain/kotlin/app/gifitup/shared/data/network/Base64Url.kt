package app.gifitup.shared.data.network

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Strict base64url without padding, the encoding of every byte field in the auth messages (ADR-0009). Same rule as the
 * server's: one spelling per value, so the alphabet is checked before decoding and the bytes must re-encode to the
 * same text. Returns null on any violation, never throws.
 */
@OptIn(ExperimentalEncodingApi::class)
internal object Base64Url {
    private val codec = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
    private val ALPHABET = Regex("^[A-Za-z0-9_-]*$")

    fun encode(bytes: ByteArray): String = codec.encode(bytes)

    /** The decoded bytes if [text] is canonical and decodes to a length in [sizes]. */
    fun decode(
        text: String,
        sizes: IntRange,
    ): ByteArray? {
        val bytes = text.takeIf { it.length <= encodedLength(sizes.last) && ALPHABET.matches(it) }?.let(::decodeOrNull)
        return bytes?.takeIf { it.size in sizes && encode(it) == text }
    }

    private fun decodeOrNull(text: String): ByteArray? =
        try {
            codec.decode(text)
        } catch (ignored: IllegalArgumentException) {
            null
        }

    private fun encodedLength(size: Int): Int = (size * BITS_PER_BYTE + BITS_PER_CHAR - 1) / BITS_PER_CHAR

    private const val BITS_PER_BYTE = 8
    private const val BITS_PER_CHAR = 6
}
