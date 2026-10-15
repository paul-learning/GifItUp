package app.gifitup.mls

import app.gifitup.shared.data.crypto.MlsErrorCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Default-deny classification of what the wasm module throws (ADR-0007, #66 SEC-3). */
class ClassifyJsErrorTest {
    @Test
    fun plainErrorWithACodeNameIsTyped() {
        assertEquals(MlsErrorCode.InvalidInput, classifyJsError(plainError("InvalidInput")))
        assertEquals(MlsErrorCode.Poisoned, classifyJsError(plainError("Poisoned")))
    }

    @Test
    fun everythingElseMeansTheModuleIsDead() {
        assertNull(classifyJsError(runtimeError("InvalidInput")), "a trap, even with a code-name message")
        assertNull(classifyJsError(rangeError("InvalidInput")))
        assertNull(classifyJsError(plainError("invalid input: details")))
        assertNull(classifyJsError(plainError("SomethingNew")))
        assertNull(classifyJsError("InvalidInput".toJsString()))
        assertNull(classifyJsError(null))
    }
}

@Suppress("UnusedParameter") // Read by the js() bodies, which detekt can't see.
private fun plainError(message: String): JsAny = js("new Error(message)")

@Suppress("UnusedParameter")
private fun rangeError(message: String): JsAny = js("new RangeError(message)")

@Suppress("UnusedParameter")
private fun runtimeError(message: String): JsAny = js("new WebAssembly.RuntimeError(message)")
