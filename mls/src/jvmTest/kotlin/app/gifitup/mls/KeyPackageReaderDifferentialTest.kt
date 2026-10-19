package app.gifitup.mls

import app.gifitup.keypackage.KeyPackageReader
import app.gifitup.keypackage.KeyPackageReading
import app.gifitup.keypackage.KeyPackageSpec
import app.gifitup.keypackage.buildKeyPackage
import app.gifitup.shared.data.crypto.MlsClient
import app.gifitup.shared.data.crypto.MlsException
import app.gifitup.shared.data.crypto.PublicIdentity
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The server's KeyPackage reader against KeyPackages made by the real crate (ADR-0012, #14 plan SEC-7). The aim is that
 * the server accepts nothing the claimers' `inspect_key_package` would refuse, apart from the clock and the contents of the
 * capabilities, which the reader does not read. Two checks: (1) on mutations of real KeyPackages, and (2) on validly
 * signed variants, whenever the reader accepts, the crate accepts with the same fields. (The reverse need not hold:
 * the reader may be stricter.) They cover the cases listed, not every input. A change in OpenMLS's wire layout fails
 * here, not in production.
 */
class KeyPackageReaderDifferentialTest {
    private val username = "ann"
    private val ann: MlsClient = UniffiMlsClientFactory.create(username, ByteArray(KEY_SIZE) { 7 })
    private val reviewer: MlsClient = UniffiMlsClientFactory.create("bob", ByteArray(KEY_SIZE) { 9 })
    private val annKey: PublicIdentity = ann.publicIdentity()

    private fun accepted(bytes: ByteArray) = (KeyPackageReader.read(bytes) as? KeyPackageReading.Accepted)?.info

    /** The crate's verdict: its fields, or `null` if `inspect_key_package` refuses. */
    private fun crateAccepts(
        bytes: ByteArray,
        expectedUsername: String = username,
    ): PublicIdentity? =
        try {
            reviewer.inspectKeyPackage(bytes, expectedUsername)
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

    /**
     * Valid signatures, odd contents: the mutation corpus above breaks a signature or a length every time, so on its own
     * it cannot show the reader accepting something the crate refuses. Here the test-side encoder signs every variant
     * itself (capabilities, extensions, lifetimes, keys), so a variant is accepted by the reader unless a rule stops it,
     * and for each one the crate must agree (#117 SEC-1).
     */
    @Test
    fun noValidlySignedVariantIsAcceptedUnlessTheCrateAcceptsIt() {
        val now = System.currentTimeMillis() / MILLIS
        val variants = validlySignedVariants(now)

        var accepted = 0
        val disagreements = mutableListOf<String>()
        variants.forEach { (name, spec) ->
            val bytes = buildKeyPackage(spec)
            val info = accepted(bytes) ?: return@forEach
            // The clock is the upload service's rule, not the reader's: compare only where the lifetime covers the present.
            if (info.notBefore > now || info.notAfter <= now) return@forEach
            accepted++
            val crate = crateAccepts(bytes, info.identity.decodeToString())
            if (crate == null) {
                disagreements += "$name: accepted by the reader, refused by the crate"
            } else if (!crate.signaturePublicKey.contentEquals(info.signatureKey)) {
                disagreements += "$name: different signature keys"
            }
        }
        assertTrue(accepted >= MIN_ACCEPTED, "the reader accepted only $accepted of ${variants.size} variants")
        assertTrue(disagreements.isEmpty(), disagreements.joinToString("\n"))
    }

    /** Validly signed KeyPackages with odd capabilities, extensions, lifetimes and keys, built around the present. */
    private fun validlySignedVariants(now: Long): List<Pair<String, KeyPackageSpec>> {
        val variants = mutableListOf<Pair<String, KeyPackageSpec>>()

        fun variant(
            name: String,
            change: KeyPackageSpec.() -> Unit = {},
        ) {
            variants += name to KeyPackageSpec(notBefore = now - HOUR, notAfter = now + 30 * DAY).apply(change)
        }
        variant("base")
        variant("other identity") { identity = "bob".encodeToByteArray() }
        listOf(listOf(), listOf(2), listOf(1, 2), listOf(0)).forEach { versions ->
            variant("versions $versions") { capabilityVersions = versions }
        }
        listOf(listOf(), listOf(2), listOf(1), listOf(3, 1)).forEach { suites ->
            variant("suites $suites") { capabilitySuites = suites }
        }
        listOf(listOf(), listOf(2), listOf(1, 2)).forEach { credentials ->
            variant("credentials $credentials") { capabilityCredentials = credentials }
        }
        listOf(listOf(0x000a), listOf(0x1234), listOf(0x0002)).forEach { extensions ->
            variant("capability extensions $extensions") { capabilityExtensions = extensions }
            variant("leaf extensions $extensions, listed") {
                capabilityExtensions = extensions
                leafExtensions = extensions.map { it to byteArrayOf(1) }
            }
            variant("leaf extensions $extensions, unlisted") { leafExtensions = extensions.map { it to byteArrayOf(1) } }
            variant("key package extensions $extensions") { keyPackageExtensions = extensions.map { it to byteArrayOf(1) } }
        }
        variant("proposals") { capabilityProposals = listOf(1, 2) }
        variant("key package extension, empty data") { keyPackageExtensions = listOf(0x1234 to ByteArray(0)) }
        variant("lifetime: 84 days") { notAfter = now + 84 * DAY - HOUR }
        variant("lifetime: just over the limit") {
            notBefore = now - HOUR
            notAfter = now - HOUR + MAX_RANGE + 1
        }
        variant("lifetime: 10 years") { notAfter = now + 3650 * DAY }
        variant("lifetime: reversed") {
            notBefore = now + DAY
            notAfter = now - DAY
        }
        variant("lifetime: not yet valid") {
            notBefore = now + 10 * DAY
            notAfter = now + 20 * DAY
        }
        variant("lifetime: expired") {
            notBefore = now - 20 * DAY
            notAfter = now - 10 * DAY
        }
        variant("lifetime: empty") {
            notBefore = now
            notAfter = now
        }
        variant("init key equals encryption key") { initKey = encryptionKey }
        variant("credential: x509") { credentialType = 2 }
        variant("long identity") { identity = "a".repeat(32).encodeToByteArray() }
        variant("empty extension data in the leaf") { leafExtensions = listOf(0x0002 to ByteArray(0)) }
        return variants
    }

    private companion object {
        const val MILLIS = 1000L
        const val HOUR = 3600L
        const val DAY = 86_400L
        const val MAX_RANGE = 84 * DAY + HOUR
        const val MIN_ACCEPTED = 8
        const val KEY_SIZE = 32
        const val KEY_PACKAGES = 20
        const val MUTATED_KEY_PACKAGES = 4
        const val MIN_MUTANTS = 3000
        val MASKS = intArrayOf(0x01, 0x80, 0xff)
    }
}
