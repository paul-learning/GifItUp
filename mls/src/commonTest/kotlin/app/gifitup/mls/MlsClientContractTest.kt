package app.gifitup.mls

import app.gifitup.shared.data.crypto.MlsClientFactory
import app.gifitup.shared.data.crypto.MlsErrorCode
import app.gifitup.shared.data.crypto.MlsException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
    }
}
