package app.gifitup.mls

import app.gifitup.shared.data.crypto.AuthPurpose
import app.gifitup.shared.data.crypto.MlsClientFactory
import app.gifitup.shared.data.crypto.MlsErrorCode
import app.gifitup.shared.data.crypto.MlsException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The platform's real [MlsClientFactory] (uniffi on JVM, wasm-bindgen in the browser). */
expect suspend fun platformMlsClientFactory(): MlsClientFactory

/**
 * One contract for every MlsClient implementation (ADR-0007, ARCH-M0-3), run on the JVM (host library via
 * JNA) and in headless Chrome. The Rust rules themselves are tested in `crypto/`; this checks that they
 * survive the bindings unchanged.
 */
class MlsClientContractTest {
    private fun key() = ByteArray(KEY_SIZE) { 7 }

    private fun assertCode(
        code: MlsErrorCode,
        block: () -> Unit,
    ) {
        val e = assertFailsWith<MlsException> { block() }
        assertEquals(code, e.code)
        assertNull(e.message, "no detail crosses the FFI")
        assertNull(e.cause)
    }

    @Test
    fun createsAnIdentity() =
        runTest {
            platformMlsClientFactory().create("alice", key()).use { alice ->
                val identity = alice.publicIdentity()
                assertEquals("alice", identity.username)
                assertEquals(SIGNATURE_KEY_SIZE, identity.signaturePublicKey.size)
            }
        }

    @Test
    fun rejectsBadUsernamesAndKeysAndZeroesTheKey() =
        runTest {
            val factory = platformMlsClientFactory()
            assertCode(MlsErrorCode.InvalidUsername) { factory.create("Alice", key()) }
            assertCode(MlsErrorCode.InvalidInput) { factory.create("alice", ByteArray(16)) }
            val snapshotKey = key()
            factory.create("alice", snapshotKey).close()
            assertContentEquals(ByteArray(KEY_SIZE), snapshotKey)
        }

    @Test
    fun keyPackagesCanBeInspectedByOthers() =
        runTest {
            val factory = platformMlsClientFactory()
            factory.create("alice", key()).use { alice ->
                factory.create("bob", key()).use { bob ->
                    val created = bob.createKeyPackages(3)
                    assertEquals(3, created.size)
                    for (kp in created) {
                        assertEquals(REF_SIZE, kp.keyPackageRef.size)
                        assertTrue(kp.notAfterEpochSeconds > YEAR_2026)
                        assertEquals(bob.publicIdentity(), alice.inspectKeyPackage(kp.keyPackage, "bob"))
                    }
                    val first = created.first().keyPackage
                    assertCode(MlsErrorCode.InvalidKeyPackage) { alice.inspectKeyPackage(first, "carol") }
                    assertCode(MlsErrorCode.InvalidKeyPackage) { alice.inspectKeyPackage(byteArrayOf(1, 2), "bob") }
                    assertCode(MlsErrorCode.InvalidInput) { bob.createKeyPackages(0) }
                    assertCode(MlsErrorCode.InvalidInput) { bob.createKeyPackages(MAX_PER_CALL + 1) }
                    assertEquals(0, bob.pruneExpiredKeyPackages())
                }
            }
        }

    /**
     * The fingerprint against the real crate (ADR-0014). The vectors were made outside the crate:
     * `hashlib.sha256(b"gifitup/fingerprint/v1\0" + key).digest()[:12]` in Python.
     */
    @Test
    fun fingerprintMatchesIndependentVectorsAndRefusesOtherLengths() =
        runTest {
            platformMlsClientFactory().create("bob", key()).use { bob ->
                assertEquals("202f30067a34946fb860eeb9", hex(bob.fingerprint(ByteArray(32))))
                assertEquals("fcb427388f0d94f10ff19869", hex(bob.fingerprint(ByteArray(32) { 0x80.toByte() })))
                assertEquals("1dc540e603af69e24ae30a6e", hex(bob.fingerprint(ByteArray(32) { it.toByte() })))
                assertEquals("67a951281dedf1d856b7b9c7", hex(bob.fingerprint(ByteArray(32) { 0xFF.toByte() })))
                listOf(0, 1, 31, 33, 64).forEach { size ->
                    assertCode(MlsErrorCode.InvalidInput) { bob.fingerprint(ByteArray(size)) }
                }
                assertEquals("202f30067a34946fb860eeb9", hex(bob.fingerprint(ByteArray(32))), "a refused call leaves the client usable")
            }
        }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    @Test
    fun createdKeyPackagesBeyondTheCapDropTheOldest() =
        runTest {
            platformMlsClientFactory().create("bob", key()).use { bob ->
                // 100 + 100 fill the cap; 100 more must still succeed (the oldest give way, #121) and be new.
                val first = bob.createKeyPackages(MAX_PER_CALL)
                bob.createKeyPackages(MAX_PER_CALL)
                val atCap = bob.exportState().size
                val more = bob.createKeyPackages(MAX_PER_CALL)
                assertEquals(MAX_PER_CALL, more.size)
                val firstRefs = first.map { it.keyPackageRef.toList() }.toSet()
                assertTrue(more.none { it.keyPackageRef.toList() in firstRefs })
                // The dropped private keys are gone from the state: it does not grow with 100 more KeyPackages.
                assertTrue(bob.exportState().size < atCap + atCap / 20, "state grew: dropped keys are still stored")
            }
        }

    @Test
    fun signAuthIsA64ByteDeterministicSignatureOverEveryField() =
        runTest {
            platformMlsClientFactory().create("alice", key()).use { alice ->
                val challenge = ByteArray(32) { 3 }
                val register = alice.signAuth(AuthPurpose.Register, challenge, ORIGIN, "alice")
                assertEquals(SIGNATURE_SIZE, register.size)
                assertContentEquals(register, alice.signAuth(AuthPurpose.Register, challenge, ORIGIN, "alice"), "Ed25519 is deterministic")

                val login = alice.signAuth(AuthPurpose.Login, challenge, ORIGIN, DEVICE)
                val others =
                    listOf(
                        register,
                        alice.signAuth(AuthPurpose.Login, ByteArray(32) { 4 }, ORIGIN, DEVICE),
                        alice.signAuth(AuthPurpose.Login, challenge, "https://localhost:8443", DEVICE),
                        alice.signAuth(AuthPurpose.Login, challenge, ORIGIN, DEVICE.replace('0', '1')),
                    )
                others.forEach { assertFalse(it.contentEquals(login)) }
            }
        }

    @Test
    fun signAuthRejectsInputsOutsideTheRules() =
        runTest {
            platformMlsClientFactory().create("alice", key()).use { alice ->
                val challenge = ByteArray(32)
                assertCode(MlsErrorCode.InvalidInput) { alice.signAuth(AuthPurpose.Register, challenge, ORIGIN, "bob") }
                assertCode(MlsErrorCode.InvalidInput) { alice.signAuth(AuthPurpose.Login, challenge, ORIGIN, "alice") }
                assertCode(MlsErrorCode.InvalidInput) { alice.signAuth(AuthPurpose.Login, challenge, ORIGIN, DEVICE.uppercase()) }
                assertCode(MlsErrorCode.InvalidInput) { alice.signAuth(AuthPurpose.Login, ByteArray(15), ORIGIN, DEVICE) }
                assertCode(MlsErrorCode.InvalidInput) { alice.signAuth(AuthPurpose.Login, ByteArray(65), ORIGIN, DEVICE) }
                listOf("$ORIGIN/", "https://GifItUp.example.org", "http://gifitup.example.org", "$ORIGIN:443").forEach { bad ->
                    assertCode(MlsErrorCode.InvalidInput) { alice.signAuth(AuthPurpose.Login, challenge, bad, DEVICE) }
                }
                // A rejected call doesn't harm the client.
                assertEquals("alice", alice.publicIdentity().username)
            }
        }

    @Test
    fun stateSurvivesExportAndRestoreWithTheSameKeyOnly() =
        runTest {
            val factory = platformMlsClientFactory()
            factory.create("alice", key()).use { alice ->
                alice.createKeyPackages(2)
                val state = alice.exportState()
                factory.restore(state, key()).use { restored ->
                    assertEquals(alice.publicIdentity(), restored.publicIdentity())
                }
                assertCode(MlsErrorCode.SnapshotAuth) { factory.restore(state, ByteArray(KEY_SIZE) { 8 }) }
                assertCode(MlsErrorCode.SnapshotInvalid) { factory.restore(byteArrayOf(1, 2, 3), key()) }
            }
        }

    @Test
    fun closedClientRefusesEveryCall() =
        runTest {
            val alice = platformMlsClientFactory().create("alice", key())
            alice.close()
            assertCode(MlsErrorCode.Poisoned) { alice.exportState() }
            assertCode(MlsErrorCode.Poisoned) { alice.publicIdentity() }
        }

    private companion object {
        const val KEY_SIZE = 32
        const val SIGNATURE_KEY_SIZE = 32
        const val REF_SIZE = 32
        const val MAX_PER_CALL = 100
        const val YEAR_2026 = 1_767_225_600L
        const val SIGNATURE_SIZE = 64
        const val ORIGIN = "https://gifitup.example.org"
        const val DEVICE = "123e4567-e89b-12d3-a456-426614174000"
    }
}
