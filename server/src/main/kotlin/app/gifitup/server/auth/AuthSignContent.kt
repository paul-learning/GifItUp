package app.gifitup.server.auth

import java.io.ByteArrayOutputStream

/** The label of the signed content: `"MLS 1.0 "` plus our own, which no MLS signature uses (ADR-0009). */
internal const val AUTH_SIGNATURE_LABEL = "MLS 1.0 GifItUp auth v1"

/**
 * Rebuilds the exact bytes a device signs (`auth_sign_content` in crypto/src/auth.rs, ADR-0009): MLS `SignWithLabel`
 * framing, `SignContent { label<V>; content<V> }` with `content = purpose ‖ challenge<V> ‖ origin<V> ‖ subject<V>`.
 * `<V>` is a vector with a minimal RFC 9000 varint length (RFC 9420 §2.1.2). The server always builds these bytes
 * from what it knows (its own origin, the challenge it checked, the subject it expects), never from bytes the client
 * sends. Checked against the crate's committed test vectors.
 */
internal fun authSignContent(
    purpose: AuthPurpose,
    challenge: ByteArray,
    origin: String,
    subject: String,
): ByteArray {
    val content =
        ByteArrayOutputStream()
            .apply {
                write(purpose.wire)
                writeVector(challenge)
                writeVector(origin.encodeToByteArray())
                writeVector(subject.encodeToByteArray())
            }.toByteArray()
    return ByteArrayOutputStream()
        .apply {
            writeVector(AUTH_SIGNATURE_LABEL.encodeToByteArray())
            writeVector(content)
        }.toByteArray()
}

/** Writes `length ‖ bytes` with the length as a minimal RFC 9000 variable-length integer (1, 2 or 4 bytes). */
private fun ByteArrayOutputStream.writeVector(bytes: ByteArray) {
    val n = bytes.size
    when {
        n < ONE_BYTE_LIMIT -> {
            write(n)
        }

        n < TWO_BYTE_LIMIT -> {
            write(TWO_BYTE_PREFIX or (n shr BYTE_BITS))
            write(n and BYTE_MASK)
        }

        n < FOUR_BYTE_LIMIT -> {
            write(FOUR_BYTE_PREFIX or (n shr THREE_BYTES))
            write((n shr TWO_BYTES) and BYTE_MASK)
            write((n shr BYTE_BITS) and BYTE_MASK)
            write(n and BYTE_MASK)
        }

        else -> {
            throw IllegalArgumentException("vector too long")
        }
    }
    write(bytes)
}

private const val ONE_BYTE_LIMIT = 1 shl 6
private const val TWO_BYTE_LIMIT = 1 shl 14
private const val FOUR_BYTE_LIMIT = 1 shl 30
private const val TWO_BYTE_PREFIX = 0x40
private const val FOUR_BYTE_PREFIX = 0x80
private const val BYTE_BITS = 8
private const val TWO_BYTES = 2 * BYTE_BITS
private const val THREE_BYTES = 3 * BYTE_BITS
private const val BYTE_MASK = 0xff
