package app.gifitup.keypackage

import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SignatureVerifierTest {
    /** RFC 8032 §7.1, TEST 1 (empty message) and TEST 2 (one byte). */
    private val rfcVectors =
        listOf(
            Triple(
                "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a",
                "",
                "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e06522490155" +
                    "5fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b",
            ),
            Triple(
                "3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c",
                "72",
                "92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da" +
                    "085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00",
            ),
        )

    @Test
    fun rfc8032VectorsVerify() {
        rfcVectors.forEach { (key, message, signature) ->
            val publicKey = assertNotNull(SignatureVerifier.decodePublicKey(hex(key)))
            assertTrue(SignatureVerifier.verify(publicKey, hex(message), hex(signature)))
            assertFalse(SignatureVerifier.verify(publicKey, hex(message) + 0, hex(signature)), "another message")
        }
    }

    /** SEC-9: S + L is the same signature mathematically but not canonical; it must be refused. */
    @Test
    fun nonCanonicalScalarIsRefused() {
        val (key, message, signature) = rfcVectors.first()
        val bytes = hex(signature)
        val s = BigInteger(1, bytes.copyOfRange(32, 64).reversedArray()).add(GROUP_ORDER)
        val sPlusL = s.toByteArray().reversedArray().copyOf(32)
        val malleated = bytes.copyOfRange(0, 32) + sPlusL

        assertFalse(SignatureVerifier.verify(assertNotNull(SignatureVerifier.decodePublicKey(hex(key))), hex(message), malleated))
    }

    @Test
    fun wrongSizesAreRefusedWithoutThrowing() {
        val (key, message, signature) = rfcVectors.first()
        assertNull(SignatureVerifier.decodePublicKey(hex(key).copyOf(31)))
        assertNull(SignatureVerifier.decodePublicKey(hex(key) + 0))
        val publicKey = assertNotNull(SignatureVerifier.decodePublicKey(hex(key)))
        assertFalse(SignatureVerifier.verify(publicKey, hex(message), hex(signature).copyOf(63)))
        assertFalse(SignatureVerifier.verify(publicKey, hex(message), ByteArray(64)))
    }

    /** #71 CR-1, SEC-28: KeyFactory alone accepts these; decodePublicKey must not. */
    @Test
    fun nonCanonicalAndOffCurveKeysAreNull() {
        // y >= p: 2^255 - 1 and p + 2 (both with the sign bit clear), and 2^255 - 1 with it set.
        listOf("ff".repeat(31) + "7f", "ef" + "ff".repeat(30) + "7f", "ff".repeat(32)).forEach { key ->
            assertNull(SignatureVerifier.decodePublicKey(hex(key)), key)
        }
        val offCurve = (2..50).map(::encodeY).filterNot(::isOnCurve).take(5)
        assertEquals(5, offCurve.size)
        offCurve.forEach { key -> assertNull(SignatureVerifier.decodePublicKey(key), key.joinToString("") { "%02x".format(it) }) }
        // And an on-curve, full-order key still decodes.
        assertNotNull(SignatureVerifier.decodePublicKey(hex(rfcVectors.first().first)))
    }

    @Test
    fun hexRefusesMalformedInput() {
        listOf("abc", "AB", "zz", "0x00").forEach { bad -> assertFailsWith<IllegalArgumentException>(bad) { hex(bad) } }
    }

    @Test
    fun smallOrderKeysAreRefusedWithAndWithoutTheSignBit() {
        SMALL_ORDER.forEach { encoded ->
            val key = hex(encoded)
            assertNull(SignatureVerifier.decodePublicKey(key), encoded)
            val withSign = key.copyOf().also { it[31] = (it[31].toInt() or 0x80).toByte() }
            assertNull(SignatureVerifier.decodePublicKey(withSign), "$encoded with sign bit")
        }
    }

    /** The blocklist is right: every canonical entry decodes to a point P with 8·P = identity. */
    @Test
    fun blocklistedPointsReallyHaveSmallOrder() {
        SMALL_ORDER.filter { BigInteger(1, hex(it).reversedArray()) < P }.forEach { encoded ->
            val point = decode(hex(encoded))
            var q = point
            repeat(3) { q = add(q, q) }
            assertEquals(Pair(BigInteger.ZERO, BigInteger.ONE), q, encoded)
        }
    }

    private companion object {
        val P: BigInteger = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19))
        val D: BigInteger = BigInteger.valueOf(-121665).multiply(BigInteger.valueOf(121666).modInverse(P)).mod(P)
        val GROUP_ORDER: BigInteger = BigInteger.ONE.shiftLeft(252).add(BigInteger("27742317777372353535851937790883648493"))
        val SQRT_M1: BigInteger = BigInteger.TWO.modPow(P.subtract(BigInteger.ONE).shiftRight(2), P)

        val SMALL_ORDER =
            listOf(
                "0000000000000000000000000000000000000000000000000000000000000000",
                "0100000000000000000000000000000000000000000000000000000000000000",
                "26e8958fc2b227b045c3f489f2ef98f0d5dfac05d3c63339b13802886d53fc05",
                "c7176a703d4dd84fba3c0b760d10670f2a2053fa2c39ccc64ec7fd7792ac037a",
                "ecffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f",
                "edffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f",
                "eeffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f",
            )

        /** The 32-byte little-endian encoding of y (sign bit clear). */
        fun encodeY(y: Int): ByteArray =
            BigInteger
                .valueOf(y.toLong())
                .toByteArray()
                .reversedArray()
                .copyOf(32)

        /** Whether some x satisfies -x² + y² = 1 + d·x²·y², i.e. (y² - 1) / (d·y² + 1) is a square mod p (Euler). */
        fun isOnCurve(encoded: ByteArray): Boolean {
            val y = BigInteger(1, encoded.reversedArray())
            val x2 =
                y
                    .pow(2)
                    .subtract(BigInteger.ONE)
                    .multiply(D.multiply(y.pow(2)).add(BigInteger.ONE).modInverse(P))
                    .mod(P)
            return x2 == BigInteger.ZERO || x2.modPow(P.subtract(BigInteger.ONE).shiftRight(1), P) == BigInteger.ONE
        }

        /** RFC 8032 §5.1.3 point decoding (test-only arithmetic, to check the blocklist). */
        fun decode(encoded: ByteArray): Pair<BigInteger, BigInteger> {
            val bytes = encoded.reversedArray()
            val sign = (bytes[0].toInt() shr 7) and 1
            bytes[0] = (bytes[0].toInt() and 0x7f).toByte()
            val y = BigInteger(1, bytes)
            val u = y.pow(2).subtract(BigInteger.ONE).mod(P)
            val v = D.multiply(y.pow(2)).add(BigInteger.ONE).mod(P)
            var x = u.multiply(v.modInverse(P)).modPow(P.add(BigInteger.valueOf(3)).shiftRight(3), P)
            if (v.multiply(x.pow(2)).subtract(u).mod(P) != BigInteger.ZERO) x = x.multiply(SQRT_M1).mod(P)
            if (x.testBit(0) != (sign == 1) && x != BigInteger.ZERO) x = P.subtract(x)
            return x to y
        }

        /** Affine twisted Edwards addition (a = -1). */
        fun add(
            p1: Pair<BigInteger, BigInteger>,
            p2: Pair<BigInteger, BigInteger>,
        ): Pair<BigInteger, BigInteger> {
            val (x1, y1) = p1
            val (x2, y2) = p2
            val t =
                D
                    .multiply(x1)
                    .multiply(x2)
                    .multiply(y1)
                    .multiply(y2)
                    .mod(P)
            val x =
                x1
                    .multiply(y2)
                    .add(y1.multiply(x2))
                    .multiply(BigInteger.ONE.add(t).modInverse(P))
                    .mod(P)
            val y =
                y1
                    .multiply(y2)
                    .add(x1.multiply(x2))
                    .multiply(
                        BigInteger.ONE
                            .subtract(t)
                            .mod(P)
                            .modInverse(P),
                    ).mod(P)
            return x to y
        }
    }
}
