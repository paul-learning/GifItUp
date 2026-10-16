package app.gifitup.server.auth

import java.math.BigInteger
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/**
 * Ed25519 verification of device signatures (ADR-0009) with the JDK's own implementation: no new dependency, and no
 * hand-written point decoding. A raw 32-byte key is wrapped in the fixed X.509 SubjectPublicKeyInfo prefix for Ed25519
 * (RFC 8410) and decoded by `KeyFactory` (#68 round 0, SEC-9).
 *
 * Nothing here throws: every failure is `null` (key) or `false` (signature), so callers map it to a closed 400 or 401,
 * never a 500 with details.
 */
internal object SignatureVerifier {
    const val PUBLIC_KEY_SIZE = 32
    const val SIGNATURE_SIZE = 64

    /** RFC 8410 SubjectPublicKeyInfo header for an Ed25519 key: SEQUENCE { AlgorithmIdentifier { 1.3.101.112 }, BIT STRING }. */
    private val SPKI_PREFIX = hex("302a300506032b6570032100")

    /** The order of the Ed25519 base point, L = 2^252 + 27742317777372353535851937790883648493 (RFC 8032 §5.1). */
    private val GROUP_ORDER = BigInteger.ONE.shiftLeft(GROUP_ORDER_BITS).add(BigInteger("27742317777372353535851937790883648493"))

    /**
     * Encodings of the small-order points (order 1, 2, 4 or 8), compared with the sign bit cleared, as libsodium's
     * `ge25519_has_small_order` does. A key with one of them would "verify" signatures for many messages; only the
     * registrant would be hurt, but #14 will trust registered keys, so they are refused at registration.
     */
    private val SMALL_ORDER_POINTS =
        listOf(
            "0000000000000000000000000000000000000000000000000000000000000000",
            "0100000000000000000000000000000000000000000000000000000000000000",
            "26e8958fc2b227b045c3f489f2ef98f0d5dfac05d3c63339b13802886d53fc05",
            "c7176a703d4dd84fba3c0b760d10670f2a2053fa2c39ccc64ec7fd7792ac037a",
            "ecffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f",
            "edffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f",
            "eeffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f",
        ).map(::hex)

    /** Decodes a raw Ed25519 public key; `null` if it isn't 32 bytes, is a small-order point, or doesn't decode. */
    fun decodePublicKey(raw: ByteArray): PublicKey? {
        if (raw.size != PUBLIC_KEY_SIZE || isSmallOrder(raw)) return null
        return try {
            KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(SPKI_PREFIX + raw))
        } catch (_: GeneralSecurityException) {
            null
        }
    }

    /**
     * True only for a valid Ed25519 signature of [message] by [key]. The scalar S must be below the group order (RFC 8032
     * §5.1.7), checked here as well, so a signature with S + L is rejected whatever the JDK version does (SEC-9).
     */
    fun verify(
        key: PublicKey,
        message: ByteArray,
        signature: ByteArray,
    ): Boolean {
        if (signature.size != SIGNATURE_SIZE || !scalarIsCanonical(signature)) return false
        return try {
            Signature.getInstance("Ed25519").run {
                initVerify(key)
                update(message)
                verify(signature)
            }
        } catch (_: GeneralSecurityException) {
            false
        }
    }

    private fun isSmallOrder(raw: ByteArray): Boolean {
        val cleared = raw.copyOf().also { it[PUBLIC_KEY_SIZE - 1] = (it[PUBLIC_KEY_SIZE - 1].toInt() and SIGN_BIT_MASK).toByte() }
        return SMALL_ORDER_POINTS.any { it.contentEquals(cleared) }
    }

    /** S is the second half of the signature, little-endian. */
    private fun scalarIsCanonical(signature: ByteArray): Boolean {
        val s = BigInteger(1, signature.copyOfRange(PUBLIC_KEY_SIZE, SIGNATURE_SIZE).reversedArray())
        return s < GROUP_ORDER
    }

    private const val SIGN_BIT_MASK = 0x7f
    private const val GROUP_ORDER_BITS = 252
}

/** Decodes lowercase hex (constants and test vectors only). */
internal fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(HEX_RADIX).toByte() }

private const val HEX_RADIX = 16
