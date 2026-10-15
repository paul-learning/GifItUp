package app.gifitup.mls

import app.gifitup.shared.data.crypto.MlsErrorCode
import app.gifitup.shared.data.crypto.MlsException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The web binding's safety rules, without a real trap (that test comes with #11 PR 4; #66 CR-2). */
class GuardedTest {
    @AfterTest
    fun reviveModule() {
        WasmModule.dead = false
    }

    @Test
    fun typedErrorKeepsTheModuleAlive() {
        val e = assertFailsWith<MlsException> { guarded { throwJs(plainErrorOf("InvalidInput")) } }
        assertEquals(MlsErrorCode.InvalidInput, e.code)
        assertNull(e.message)
        assertFalse(WasmModule.dead)
    }

    @Test
    fun anyOtherJsErrorKillsTheModuleForEveryLaterCall() {
        val e = assertFailsWith<MlsException> { guarded { throwJs(runtimeErrorOf("unreachable")) } }
        assertEquals(MlsErrorCode.Poisoned, e.code)
        assertTrue(WasmModule.dead)
        val later = assertFailsWith<MlsException> { guarded<JsAny?> { null } }
        assertEquals(MlsErrorCode.Poisoned, later.code)
    }

    @Test
    fun kotlinExceptionsBecomeInternal() {
        val e = assertFailsWith<MlsException> { kotlinErrorsAsInternal<Unit> { error("bug with details") } }
        assertEquals(MlsErrorCode.Internal, e.code)
        assertNull(e.message)
        assertNull(e.cause)
    }
}

@Suppress("UnusedParameter") // Read by the js() bodies, which detekt can't see.
private fun throwJs(error: JsAny): JsAny? = js("(() => { throw error; })()")

@Suppress("UnusedParameter")
private fun plainErrorOf(message: String): JsAny = js("new Error(message)")

@Suppress("UnusedParameter")
private fun runtimeErrorOf(message: String): JsAny = js("new WebAssembly.RuntimeError(message)")
