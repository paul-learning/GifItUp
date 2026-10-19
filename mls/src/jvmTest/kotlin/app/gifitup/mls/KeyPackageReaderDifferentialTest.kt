package app.gifitup.mls

import app.gifitup.keypackage.KeyPackageReader
import app.gifitup.keypackage.KeyPackageReading
import app.gifitup.shared.data.crypto.MlsClient
import app.gifitup.shared.data.crypto.MlsException
import app.gifitup.shared.data.crypto.PublicIdentity
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The server's KeyPackage reader against KeyPackages made by the real crate (ADR-0012, #14 plan SEC-7). The server must
 * accept nothing the claimers' `inspect_key_package` would refuse, so on a corpus of mutations of real KeyPackages:
 * whenever the reader accepts, the crate accepts with the same fields. (The reverse need not hold: the reader may be
 * stricter.) A change in OpenMLS's wire layout fails here, not in production.
 */
class KeyPackageReaderDifferentialTest {
    private val username = "ann"
    private val ann: MlsClient = UniffiMlsClientFactory.create(username, ByteArray(KEY_SIZE) { 7 })
    private val reviewer: MlsClient = UniffiMlsClientFactory.create("bob", ByteArray(KEY_SIZE) { 9 })
    private val annKey: PublicIdentity = ann.publicIdentity()

    private fun accepted(bytes: ByteArray) = (KeyPackageReader.read(bytes) as? KeyPackageReading.Accepted)?.info

    /** The crate's verdict: its fields, or `null` if `inspect_key_package` refuses. */
    private fun crateAccepts(bytes: ByteArray): PublicIdentity? =
        try {
            reviewer.inspectKeyPackage(bytes, username)
        } catch (_: MlsException) {
            null
        }

    @Test
    fun everyRealKeyPackageIsAcceptedWithTheCratesFields() {
        val created = ann.createKeyPackages(KEY_PACKAGES)
        assertEquals(KEY_PACKAGES, created.size)
        created.forEach { kp ->
            val info = assertIs<KeyPackageReading.Accepted>(KeyPackageReader.read(kp.keyPackage)).info
            assertEquals(1, info.ciphersuite)
            assertEquals(username, info.identity.decodeToString())
            assertContentEquals(annKey.signaturePublicKey, info.signatureKey)
            assertEquals(kp.notAfterEpochSeconds, info.notAfter)
            assertTrue(info.notBefore < info.notAfter)
            assertEquals(annKey.signaturePublicKey.toList(), crateAccepts(kp.keyPackage)?.signaturePublicKey?.toList())
        }
    }

    @Test
    fun noMutantIsAcceptedUnlessTheCrateAcceptsItWithTheSameFields() {
        val corpus = ann.createKeyPackages(MUTATED_KEY_PACKAGES).map { it.keyPackage }
        var mutants = 0
        corpus.forEach { original ->
            val variants = mutableListOf<ByteArray>()
            for (i in original.indices) {
                for (mask in MASKS) variants += original.copyOf().also { it[i] = (it[i].toInt() xor mask).toByte() }
            }
            for (length in original.indices) variants += original.copyOf(length)
            variants += original + 0
            variants += original + original
            variants += byteArrayOf(0) + original
            variants.forEach { mutant ->
                mutants++
                val info = accepted(mutant) ?: return@forEach
                val crate = crateAccepts(mutant)
                assertTrue(crate != null, "the reader accepted a mutant that the crate refuses")
                assertContentEquals(crate.signaturePublicKey, info.signatureKey, "same signature key")
                assertEquals(crate.username, info.identity.decodeToString(), "same identity")
            }
        }
        assertTrue(mutants > MIN_MUTANTS, "the corpus ran ($mutants mutants)")
    }

    private companion object {
        const val KEY_SIZE = 32
        const val KEY_PACKAGES = 20
        const val MUTATED_KEY_PACKAGES = 4
        const val MIN_MUTANTS = 3000
        val MASKS = intArrayOf(0x01, 0x80, 0xff)
    }
}
