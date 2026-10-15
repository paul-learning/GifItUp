package app.gifitup.mls

import app.gifitup.shared.data.crypto.MlsErrorCode
import app.gifitup.shared.data.crypto.MlsException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * A real Rust panic through the uniffi bindings (ADR-0007). The JVM tests load a debug host library with the
 * `test-panic` feature, where `inspectKeyPackage` panics on the username "__panic__".
 */
class PanicTest {
    @Test
    fun panicPoisonsTheClientWithoutLeakingThePayload() {
        UniffiMlsClientFactory.create("alice", ByteArray(KEY_SIZE) { 7 }).use { alice ->
            val e = assertFailsWith<MlsException> { alice.inspectKeyPackage(ByteArray(0), "__panic__") }
            assertEquals(MlsErrorCode.Poisoned, e.code)
            assertNull(e.message, "the panic message never reaches Kotlin")
            assertNull(e.cause)
            // Half-updated state must never be exported (enforced in Rust, mirrored in Kotlin).
            assertEquals(MlsErrorCode.Poisoned, assertFailsWith<MlsException> { alice.exportState() }.code)
        }
    }

    @Test
    fun otherClientsKeepWorking() {
        UniffiMlsClientFactory.create("alice", ByteArray(KEY_SIZE) { 7 }).use { alice ->
            assertFailsWith<MlsException> { alice.inspectKeyPackage(ByteArray(0), "__panic__") }
        }
        UniffiMlsClientFactory.create("bob", ByteArray(KEY_SIZE) { 7 }).use { bob ->
            assertEquals("bob", bob.publicIdentity().username)
        }
    }

    private companion object {
        const val KEY_SIZE = 32
    }
}
