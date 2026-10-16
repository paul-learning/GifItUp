package app.gifitup.mls

import app.gifitup.shared.data.crypto.MlsErrorCode
import app.gifitup.shared.data.crypto.MlsException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/**
 * The web binding takes the purpose as its wire byte (crypto/src/auth.rs). The Kotlin wrapper only ever passes 1 or
 * 2; the module itself must refuse anything else, without dying.
 */
class WasmSignAuthTest {
    @Test
    fun unknownPurposeBytesAreRejected() =
        runTest {
            loadWasmMlsClientFactory()
            val native = guarded { NativeMlsClient("alice", ByteArray(KEY_SIZE) { KEY_FILL }.toUint8Array()) }
            try {
                val challenge = ByteArray(CHALLENGE_SIZE).toUint8Array()
                for (purpose in UNKNOWN_PURPOSES) {
                    val e =
                        assertFailsWith<MlsException>("purpose $purpose") {
                            guarded { native.signAuth(purpose, challenge, "https://gifitup.example.org", "alice") }
                        }
                    assertEquals(MlsErrorCode.InvalidInput, e.code)
                }
                assertFalse(WasmModule.dead)
                // Register is accepted for the client's own username.
                guarded { native.signAuth(REGISTER, challenge, "https://gifitup.example.org", "alice") }
            } finally {
                guarded<JsAny?> {
                    native.free()
                    null
                }
            }
        }

    private companion object {
        const val KEY_SIZE = 32
        const val KEY_FILL: Byte = 7
        const val CHALLENGE_SIZE = 32
        const val REGISTER = 1
        val UNKNOWN_PURPOSES = listOf(0, 3, 255)
    }
}
