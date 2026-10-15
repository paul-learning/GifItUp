package app.gifitup.mls

import app.gifitup.shared.data.crypto.MlsErrorCode
import app.gifitup.shared.data.crypto.MlsException
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import org.khronos.webgl.Uint8Array
import org.khronos.webgl.get
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A real Rust panic in the browser is a wasm trap (ADR-0007, #66 SEC-1). It runs against the separate test
 * build of the crate, so the production module the contract tests use stays alive.
 */
class TrapTest {
    @AfterTest
    fun reviveKotlinFlag() {
        WasmModule.dead = false
    }

    @Test
    fun aTrapIsCaughtOnTheCallItselfAndTheKeyIsZeroed() =
        runTest {
            initTestCrypto().await<JsAny?>()
            val key = ByteArray(KEY_SIZE) { KEY_BYTE }
            var jsKey: Uint8Array? = null

            val e =
                assertFailsWith<MlsException> {
                    withSnapshotKey(key) { k ->
                        jsKey = k
                        guarded {
                            TestNativeMlsClient("alice", k).inspectKeyPackage(Uint8Array(0), "__panic__")
                        }
                    }
                }

            assertEquals(MlsErrorCode.Poisoned, e.code, "caught on the trapping call itself")
            assertTrue(WasmModule.dead, "the module is marked dead")
            assertContentEquals(ByteArray(KEY_SIZE), key, "our copy of the key is zeroed")
            val copy = checkNotNull(jsKey)
            assertTrue((0 until copy.length).all { copy[it] == 0.toByte() }, "the key's JS copy is zeroed")
            // Every later call is refused, on the Kotlin side...
            assertEquals(MlsErrorCode.Poisoned, assertFailsWith<MlsException> { guarded<JsAny?> { null } }.code)
            // ...and by the Rust trap detector, which sees the unfinished call (constructors included).
            // The raw error is a plain Error "Poisoned" from Rust, not a second trap (#67 SEC-2).
            val raw = jsTry { TestNativeMlsClient("bob", Uint8Array(KEY_SIZE)) }
            assertTrue(raw.failed)
            assertEquals(MlsErrorCode.Poisoned, classifyJsError(raw.error), "a plain Error, not a RuntimeError")
        }

    private companion object {
        const val KEY_SIZE = 32
        const val KEY_BYTE: Byte = 7
    }
}
