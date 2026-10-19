package app.gifitup.keypackage

/**
 * What the server takes from a KeyPackage (ADR-0012). [signatureKey] and [identity] are raw bytes (the Ed25519 key, the
 * BasicCredential identity, i.e. the username); the times are Unix seconds from the leaf node's lifetime.
 */
class KeyPackageInfo(
    val ciphersuite: Int,
    val signatureKey: ByteArray,
    val identity: ByteArray,
    val notBefore: Long,
    val notAfter: Long,
) {
    override fun toString(): String = "KeyPackageInfo(ciphersuite=$ciphersuite, notBefore=$notBefore, notAfter=$notAfter)"
}

/** Why a KeyPackage was refused. Closed on purpose: the name goes to the log, never to the client (ADR-0012). */
enum class KeyPackageRejection {
    TOO_LARGE,

    /** Not a well-formed KeyPackage MLSMessage: truncated, trailing bytes, non-canonical length, a wrong field. */
    MALFORMED,

    /** Well formed, but a suite, a credential type or a version the reader doesn't support. */
    UNSUPPORTED,

    /** Carries the `last_resort` extension: not accepted in v1 (ADR-0012). */
    LAST_RESORT,

    /** A key with the wrong size, a small-order or invalid signature key, or `init_key` equal to the leaf encryption key. */
    BAD_KEYS,

    /** The KeyPackage signature or the leaf node signature does not verify. */
    BAD_SIGNATURE,
}

sealed interface KeyPackageReading {
    class Accepted(
        val info: KeyPackageInfo,
    ) : KeyPackageReading

    class Rejected(
        val reason: KeyPackageRejection,
    ) : KeyPackageReading
}

/**
 * A strict reader of one KeyPackage (RFC 9420 §10, §7.2, §2.1.2) in an MLSMessage, for the server (ADR-0012). It
 * extracts the fields the server needs and verifies the two Ed25519 signatures; it does not validate the contents of
 * capabilities and extensions (they are skipped by length), because every claimer runs the full check
 * (`inspect_key_package`, ADR-0002).
 *
 * Strict means: at most [MAX_BYTES], every length in its shortest encoding, the whole input consumed, no duplicate
 * extension types, only what suite 1 uses. Nothing here recurses or allocates more than the input, and nothing throws:
 * every problem is a [KeyPackageRejection]. The set it accepts must stay inside what the crate accepts; the `:mls` tests
 * check that on a mutation corpus of real KeyPackages.
 */
object KeyPackageReader {
    const val MAX_BYTES = 2048

    /** The suites this reader (and so the server) accepts: `MLS_128_DHKEMX25519_AES128GCM_SHA256_Ed25519` (ADR-0002). */
    val SUPPORTED_SUITES: Set<Int> = setOf(SUITE_X25519_AES128_ED25519)

    fun read(bytes: ByteArray): KeyPackageReading =
        try {
            KeyPackageReading.Accepted(parse(bytes))
        } catch (e: Rejection) {
            KeyPackageReading.Rejected(e.reason)
        }

    @Suppress("CyclomaticComplexMethod") // One straight pass over the fixed layout; splitting it would hide the order.
    private fun parse(bytes: ByteArray): KeyPackageInfo {
        if (bytes.size > MAX_BYTES) reject(KeyPackageRejection.TOO_LARGE)
        val c = Cursor(bytes)
        if (c.u16() != MLS10 || c.u16() != WIRE_FORMAT_KEY_PACKAGE) reject(KeyPackageRejection.MALFORMED)

        val keyPackageStart = c.pos
        if (c.u16() != MLS10) reject(KeyPackageRejection.UNSUPPORTED)
        val suite = c.u16()
        if (suite !in SUPPORTED_SUITES) reject(KeyPackageRejection.UNSUPPORTED)
        val initKey = c.vec()

        val leafStart = c.pos
        val encryptionKey = c.vec()
        val signatureKey = c.vec()
        if (c.u16() != CREDENTIAL_BASIC) reject(KeyPackageRejection.UNSUPPORTED)
        val identity = c.vec()
        if (identity.isEmpty() || identity.size > MAX_IDENTITY_BYTES) reject(KeyPackageRejection.MALFORMED)
        repeat(CAPABILITY_VECTORS) { c.skipVec() }
        if (c.u8() != LEAF_NODE_SOURCE_KEY_PACKAGE) reject(KeyPackageRejection.MALFORMED)
        val notBefore = c.u64()
        val notAfter = c.u64()
        c.extensions()
        val leafTbsEnd = c.pos
        val leafSignature = c.vec()

        val lastResort = c.extensions().contains(EXTENSION_LAST_RESORT)
        val keyPackageTbsEnd = c.pos
        val keyPackageSignature = c.vec()
        if (c.pos != bytes.size) reject(KeyPackageRejection.MALFORMED)

        if (lastResort) reject(KeyPackageRejection.LAST_RESORT)
        if (initKey.size != KEY_BYTES || encryptionKey.size != KEY_BYTES || signatureKey.size != KEY_BYTES) {
            reject(KeyPackageRejection.BAD_KEYS)
        }
        if (initKey.contentEquals(encryptionKey)) reject(KeyPackageRejection.BAD_KEYS)
        val publicKey = SignatureVerifier.decodePublicKey(signatureKey) ?: reject(KeyPackageRejection.BAD_KEYS)

        val leafOk = SignatureVerifier.verify(publicKey, signContent(LEAF_LABEL, bytes, leafStart, leafTbsEnd), leafSignature)
        val keyPackageOk =
            SignatureVerifier.verify(
                publicKey,
                signContent(KEY_PACKAGE_LABEL, bytes, keyPackageStart, keyPackageTbsEnd),
                keyPackageSignature,
            )
        if (!leafOk || !keyPackageOk) reject(KeyPackageRejection.BAD_SIGNATURE)
        return KeyPackageInfo(suite, signatureKey, identity, notBefore, notAfter)
    }

    /** `SignWithLabel` input (RFC 9420 §5.1.2): `opaque label<V> = "MLS 1.0 " + Label; opaque content<V>`. */
    private fun signContent(
        label: String,
        bytes: ByteArray,
        start: Int,
        end: Int,
    ): ByteArray {
        val fullLabel = (LABEL_PREFIX + label).encodeToByteArray()
        return varint(fullLabel.size) + fullLabel + varint(end - start) + bytes.copyOfRange(start, end)
    }

    /** The shortest encoding of a length (RFC 9420 §2.1.2); lengths here are below 2^14. */
    private fun varint(n: Int): ByteArray =
        if (n < VARINT_1_LIMIT) {
            byteArrayOf(n.toByte())
        } else {
            byteArrayOf((VARINT_2_PREFIX or (n shr Byte.SIZE_BITS)).toByte(), n.toByte())
        }

    private fun reject(reason: KeyPackageRejection): Nothing = throw Rejection(reason)

    /** Control flow only: no message and no stack trace, so nothing about the input can leak through it. */
    private class Rejection(
        val reason: KeyPackageRejection,
    ) : Exception(null, null, false, false)

    private class Cursor(
        private val bytes: ByteArray,
    ) {
        var pos = 0
            private set

        fun u8(): Int = need(1).let { bytes[pos++].toInt() and BYTE_MASK }

        fun u16(): Int = (u8() shl Byte.SIZE_BITS) or u8()

        /** A uint64 as a non-negative Long; a value of 2^63 or more is not a time we could mean. */
        fun u64(): Long {
            var value = 0L
            repeat(Long.SIZE_BYTES) { value = (value shl Byte.SIZE_BITS) or u8().toLong() }
            if (value < 0) reject(KeyPackageRejection.MALFORMED)
            return value
        }

        /** A length prefix in its shortest form (RFC 9420 §2.1.2): 1, 2 or 4 bytes, never the `11` prefix. */
        private fun length(): Int {
            val first = u8()
            val value =
                when (first ushr VARINT_PREFIX_SHIFT) {
                    0 -> first
                    1 -> longForm(first, extraBytes = 1, minimum = VARINT_1_LIMIT)
                    2 -> longForm(first, extraBytes = 3, minimum = VARINT_2_LIMIT)
                    else -> reject(KeyPackageRejection.MALFORMED)
                }
            need(value)
            return value
        }

        /** The 2- and 4-byte forms; a value that fits the shorter form is not canonical. */
        private fun longForm(
            first: Int,
            extraBytes: Int,
            minimum: Int,
        ): Int {
            var value = first and VARINT_VALUE_MASK
            repeat(extraBytes) { value = (value shl Byte.SIZE_BITS) or u8() }
            if (value < minimum) reject(KeyPackageRejection.MALFORMED)
            return value
        }

        fun vec(): ByteArray {
            val n = length()
            return bytes.copyOfRange(pos, pos + n).also { pos += n }
        }

        fun skipVec() {
            val n = length()
            pos += n
        }

        /** An `Extension extensions<V>`: well-formed entries, no type twice. Returns the types. */
        fun extensions(): Set<Int> {
            val n = length()
            val end = pos + n
            val types = HashSet<Int>()
            while (pos < end) {
                if (!types.add(u16())) reject(KeyPackageRejection.MALFORMED)
                val dataLength = length()
                if (pos + dataLength > end) reject(KeyPackageRejection.MALFORMED)
                pos += dataLength
            }
            if (pos != end) reject(KeyPackageRejection.MALFORMED)
            return types
        }

        private fun need(n: Int) {
            if (n < 0 || n > bytes.size - pos) reject(KeyPackageRejection.MALFORMED)
        }
    }

    private const val SUITE_X25519_AES128_ED25519 = 1
    private const val MLS10 = 1
    private const val WIRE_FORMAT_KEY_PACKAGE = 5
    private const val CREDENTIAL_BASIC = 1
    private const val LEAF_NODE_SOURCE_KEY_PACKAGE = 1
    private const val EXTENSION_LAST_RESORT = 0x000a
    private const val CAPABILITY_VECTORS = 5
    private const val KEY_BYTES = 32
    private const val MAX_IDENTITY_BYTES = 255
    private const val LABEL_PREFIX = "MLS 1.0 "
    private const val KEY_PACKAGE_LABEL = "KeyPackageTBS"
    private const val LEAF_LABEL = "LeafNodeTBS"
    private const val BYTE_MASK = 0xff
    private const val VARINT_PREFIX_SHIFT = 6
    private const val VARINT_VALUE_MASK = 0x3f
    private const val VARINT_1_LIMIT = 1 shl 6
    private const val VARINT_2_LIMIT = 1 shl 14
    private const val VARINT_2_PREFIX = 0x40
}
