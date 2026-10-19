@file:Suppress("MagicNumber") // Wire-layout test vectors.

package app.gifitup.keypackage

import java.util.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class KeyPackageReaderTest {
    private fun read(bytes: ByteArray) = KeyPackageReader.read(bytes)

    private fun assertRejected(
        reason: KeyPackageRejection,
        bytes: ByteArray,
        message: String = "",
    ) = assertEquals(reason, assertIs<KeyPackageReading.Rejected>(read(bytes), message).reason, message)

    private fun assertNotAccepted(
        bytes: ByteArray,
        message: String,
    ) = assertIs<KeyPackageReading.Rejected>(read(bytes), message)

    /** A KeyPackage made by the real crate (ann, snapshot key 32 × 0x07), so the layout is not only our own reading of the RFC. */
    @Test
    fun aKeyPackageFromTheRealCrateIsAcceptedWithTheRightFields() {
        val info = assertIs<KeyPackageReading.Accepted>(read(hex(REAL_KEY_PACKAGE))).info
        assertEquals(1, info.ciphersuite)
        assertEquals("ann", info.identity.decodeToString())
        assertContentEquals(hex(REAL_SIGNATURE_KEY), info.signatureKey)
        assertEquals(0x6ad66cdbL, info.notBefore)
        assertEquals(1_799_698_667L, info.notAfter)
    }

    @Test
    fun theEncoderAndTheReaderAgreeOnAValidKeyPackage() {
        val info = assertIs<KeyPackageReading.Accepted>(read(buildKeyPackage())).info
        assertEquals(1, info.ciphersuite)
        assertEquals("ann", info.identity.decodeToString())
        assertContentEquals(KeyPackageSpec.rawPublicKey(KeyPackageSpec.SIGNER), info.signatureKey)
        assertEquals(1_000L, info.notBefore)
        assertEquals(2_000L, info.notAfter)
    }

    @Test
    fun everyTruncationAndAnyTrailingByteIsMalformed() {
        val valid = buildKeyPackage()
        for (length in valid.indices) assertNotAccepted(valid.copyOf(length), "truncated to $length")
        assertRejected(KeyPackageRejection.MALFORMED, valid + 0)
        assertRejected(KeyPackageRejection.MALFORMED, valid + valid)
    }

    /** Every byte is covered by a signature or is the signature, so no single-byte change is accepted. */
    @Test
    fun noSingleByteChangeIsAccepted() {
        listOf(buildKeyPackage(), hex(REAL_KEY_PACKAGE)).forEach { valid ->
            for (i in valid.indices) {
                for (mask in intArrayOf(0x01, 0x80, 0xff)) {
                    val mutant = valid.copyOf().also { it[i] = (it[i].toInt() xor mask).toByte() }
                    assertNotAccepted(mutant, "byte $i xor $mask")
                }
            }
        }
    }

    @Test
    fun aLengthInANonShortestFormIsMalformed() {
        assertRejected(KeyPackageRejection.MALFORMED, buildKeyPackage(KeyPackageSpec(fatVarints = true)))
        assertRejected(KeyPackageRejection.MALFORMED, buildKeyPackage(KeyPackageSpec(fourByteVarints = true)))
        // The reserved 0b11 prefix as a vector length (after the 4-byte header, version, suite: the init key length).
        val valid = buildKeyPackage()
        assertRejected(KeyPackageRejection.MALFORMED, valid.copyOf().also { it[8] = 0xc0.toByte() })
    }

    @Test
    fun wrongHeaderFieldsAndUnsupportedChoicesAreRefused() {
        assertRejected(KeyPackageRejection.MALFORMED, buildKeyPackage(KeyPackageSpec(mlsVersion = 2)))
        assertRejected(KeyPackageRejection.MALFORMED, buildKeyPackage(KeyPackageSpec(wireFormat = 2)))
        assertRejected(KeyPackageRejection.UNSUPPORTED, buildKeyPackage(KeyPackageSpec(keyPackageVersion = 2)))
        assertRejected(KeyPackageRejection.UNSUPPORTED, buildKeyPackage(KeyPackageSpec(suite = 3)))
        assertRejected(KeyPackageRejection.UNSUPPORTED, buildKeyPackage(KeyPackageSpec(suite = 0x8001)))
        assertRejected(KeyPackageRejection.UNSUPPORTED, buildKeyPackage(KeyPackageSpec(credentialType = 2)))
        assertRejected(KeyPackageRejection.MALFORMED, buildKeyPackage(KeyPackageSpec(source = 2)))
        assertRejected(KeyPackageRejection.MALFORMED, buildKeyPackage(KeyPackageSpec(identity = ByteArray(0))))
        assertRejected(KeyPackageRejection.MALFORMED, buildKeyPackage(KeyPackageSpec(notAfter = Long.MIN_VALUE)))
    }

    @Test
    fun theLastResortExtensionIsRefusedAndSoIsEveryOtherExtension() {
        assertRejected(
            KeyPackageRejection.LAST_RESORT,
            buildKeyPackage(KeyPackageSpec(keyPackageExtensions = listOf(0x000a to ByteArray(0)))),
        )
        assertRejected(
            KeyPackageRejection.UNSUPPORTED,
            buildKeyPackage(
                KeyPackageSpec(
                    keyPackageExtensions =
                        listOf(0x0003 to byteArrayOf(1, 2)),
                ),
            ),
        )
        assertRejected(KeyPackageRejection.UNSUPPORTED, buildKeyPackage(KeyPackageSpec(leafExtensions = listOf(0x0002 to byteArrayOf(9)))))
        assertRejected(KeyPackageRejection.UNSUPPORTED, buildKeyPackage(KeyPackageSpec(leafExtensions = listOf(0x0001 to ByteArray(0)))))
    }

    @Test
    fun aLifetimeMustBeOrderedAndAtMostEightyFourDaysAndAnHour() {
        val limit = KeyPackageReader.MAX_LIFETIME_SECONDS
        assertIs<KeyPackageReading.Accepted>(read(buildKeyPackage(KeyPackageSpec(notBefore = 5_000, notAfter = 5_000 + limit))))
        assertRejected(KeyPackageRejection.BAD_LIFETIME, buildKeyPackage(KeyPackageSpec(notBefore = 5_000, notAfter = 5_000 + limit + 1)))
        assertRejected(KeyPackageRejection.BAD_LIFETIME, buildKeyPackage(KeyPackageSpec(notBefore = 5_001, notAfter = 5_000)))
        assertIs<KeyPackageReading.Accepted>(read(buildKeyPackage(KeyPackageSpec(notBefore = 5_000, notAfter = 5_000))))
        assertEquals(84L * 86_400 + 3_600, limit)
    }

    @Test
    fun aDuplicateExtensionTypeIsMalformed() {
        val twice = listOf(0x0003 to byteArrayOf(1), 0x0003 to byteArrayOf(2))
        assertRejected(KeyPackageRejection.MALFORMED, buildKeyPackage(KeyPackageSpec(keyPackageExtensions = twice)))
        assertRejected(KeyPackageRejection.MALFORMED, buildKeyPackage(KeyPackageSpec(leafExtensions = twice)))
    }

    @Test
    fun keysOfTheWrongSizeOrKindAreBadKeys() {
        assertRejected(KeyPackageRejection.BAD_KEYS, buildKeyPackage(KeyPackageSpec(initKey = ByteArray(31) { 1 })))
        assertRejected(KeyPackageRejection.BAD_KEYS, buildKeyPackage(KeyPackageSpec(encryptionKey = ByteArray(33) { 2 })))
        assertRejected(KeyPackageRejection.BAD_KEYS, buildKeyPackage(KeyPackageSpec(advertisedSignatureKey = ByteArray(31) { 3 })))
        // RFC 9420 §10.1: init_key must differ from the leaf's encryption_key.
        assertRejected(
            KeyPackageRejection.BAD_KEYS,
            buildKeyPackage(KeyPackageSpec(initKey = ByteArray(32) { 5 }, encryptionKey = ByteArray(32) { 5 })),
        )
        // A small-order signature key (the identity point) and an off-curve one.
        assertRejected(KeyPackageRejection.BAD_KEYS, buildKeyPackage(KeyPackageSpec(advertisedSignatureKey = hex("01" + "00".repeat(31)))))
        assertRejected(
            KeyPackageRejection.BAD_KEYS,
            buildKeyPackage(KeyPackageSpec(advertisedSignatureKey = ByteArray(32) { 0xff.toByte() })),
        )
    }

    @Test
    fun aSignatureByTheWrongKeyOrOfTheWrongSizeIsRefused() {
        assertRejected(KeyPackageRejection.BAD_SIGNATURE, buildKeyPackage(KeyPackageSpec(keyPackageSigner = KeyPackageSpec.OTHER)))
        assertRejected(KeyPackageRejection.BAD_SIGNATURE, buildKeyPackage(KeyPackageSpec(leafSigner = KeyPackageSpec.OTHER)))
        assertRejected(KeyPackageRejection.BAD_SIGNATURE, buildKeyPackage(KeyPackageSpec(keyPackageSignatureBytes = 63)))
        // An advertised key that is not the signer's.
        val advertised = KeyPackageSpec.rawPublicKey(KeyPackageSpec.OTHER)
        assertRejected(KeyPackageRejection.BAD_SIGNATURE, buildKeyPackage(KeyPackageSpec(advertisedSignatureKey = advertised)))
    }

    @Test
    fun aSignatureWithANonCanonicalScalarIsRefused() {
        val valid = buildKeyPackage()
        // S is the second half of the KeyPackage signature, which ends the input; S + L still fits in 32 bytes.
        val l =
            java.math.BigInteger.ONE
                .shiftLeft(252)
                .add(java.math.BigInteger("27742317777372353535851937790883648493"))
        val sOffset = valid.size - 32
        val s = java.math.BigInteger(1, valid.copyOfRange(sOffset, valid.size).reversedArray()).add(l)
        val malleated = s.toByteArray().reversedArray().copyOf(32)
        assertRejected(KeyPackageRejection.BAD_SIGNATURE, valid.copyOf().also { malleated.copyInto(it, sOffset) })
    }

    @Test
    fun anInputOverTheSizeLimitIsTooLargeBeforeAnythingIsRead() {
        assertRejected(KeyPackageRejection.TOO_LARGE, ByteArray(KeyPackageReader.MAX_BYTES + 1))
        assertRejected(KeyPackageRejection.TOO_LARGE, buildKeyPackage() + ByteArray(KeyPackageReader.MAX_BYTES))
        assertRejected(KeyPackageRejection.MALFORMED, ByteArray(KeyPackageReader.MAX_BYTES))
    }

    @Test
    fun randomAndStructuredGarbageNeverThrowsAndIsNeverAccepted() {
        val random = Random(14)
        repeat(3_000) {
            val bytes = ByteArray(random.nextInt(400)).also(random::nextBytes)
            assertNotAccepted(bytes, "random $it")
        }
        val valid = buildKeyPackage()
        repeat(3_000) {
            val mutant = valid.copyOf()
            repeat(1 + random.nextInt(4)) { mutant[random.nextInt(mutant.size)] = random.nextInt().toByte() }
            if (!mutant.contentEquals(valid)) assertNotAccepted(mutant, "mutant $it")
        }
        assertTrue(valid.size < KeyPackageReader.MAX_BYTES)
    }

    private companion object {
        /**
         * From the real crate (see the :mls differential test); the identity is "ann". Valid forever: the reader does not
         * look at the clock.
         */
        const val REAL_KEY_PACKAGE =
            "000100050001000120f97e53f7a6ac853828b369f79870ee2e5cb3b080606c2f8c9aad4f04c015aa3a20e79dbf4ef740" +
                "31763372b908bd14b829d489b5604dac8f62231b8b0e764ea63120b00fc6f1fa79b66bea9e07160243b1b612f0eb9434" +
                "b2a9a3685d451ecde0b2d2000103616e6e02000106000100020003000002000101000000006ad66cdb000000006b4538" +
                "eb004040ae87ce73633feeff3e51fbb2fe9b3b8e6d2a1d0e0721c927e8e4d2ae8271fe5102d80b86d52c61b2adb624eb" +
                "7a6de91244b8703e2f0dcd1a3638c1d5f29ad30100404079e173b31dd535d9b760986854aabc0d9e17dcbd840f24319f" +
                "448d20306c6ff4e77121775f4f351cbff45acef9b17cefab9ebbcd41efa4674e82b8b3eb712100"
        const val REAL_SIGNATURE_KEY = "b00fc6f1fa79b66bea9e07160243b1b612f0eb9434b2a9a3685d451ecde0b2d2"
    }
}
