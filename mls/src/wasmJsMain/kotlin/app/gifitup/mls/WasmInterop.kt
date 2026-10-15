@file:Suppress("MatchingDeclarationName") // JS-interop helpers; JsTryResult is just one of them.

package app.gifitup.mls

import app.gifitup.shared.data.crypto.MlsErrorCode
import app.gifitup.shared.data.crypto.MlsException
import org.khronos.webgl.Uint8Array
import org.khronos.webgl.get
import org.khronos.webgl.set

// JS interop for the wasm module: the JS try/catch around every call, error classification and byte copies.

/** What [jsTry] returns: the call's value, or what it threw. */
internal external interface JsTryResult : JsAny {
    val failed: Boolean
    val value: JsAny?
    val error: JsAny?
}

/**
 * Runs [block] inside a JavaScript try/catch. A wasm trap can't be caught by Kotlin/Wasm's own `try` (it
 * compiles to Wasm exception handling, which skips trap errors, including `finally` blocks); a JS `catch` does
 * catch it (#66 SEC-1).
 */
@Suppress("UnusedParameter") // Read by the js() body, which detekt can't see.
internal fun jsTry(block: () -> JsAny?): JsTryResult =
    js("(() => { try { return { failed: false, value: block() }; } catch (e) { return { failed: true, error: e }; } })()")

/**
 * Every call into the wasm module goes through here. Errors are classified by [classifyJsError]; a trap or
 * any unknown error marks the whole module dead (ADR-0007).
 */
internal fun <T : JsAny?> guarded(block: () -> T): T {
    if (WasmModule.dead) throw MlsException(MlsErrorCode.Poisoned)
    val result = jsTry { block() }
    if (result.failed) {
        val code = classifyJsError(result.error)
        if (code == null || code == MlsErrorCode.Poisoned) WasmModule.dead = true
        throw MlsException(code ?: MlsErrorCode.Poisoned)
    }
    @Suppress("UNCHECKED_CAST") // jsTry returns exactly what block returned.
    return result.value as T
}

/**
 * Default-deny classification by JS type: only a plain `Error` (not a subclass such as
 * `WebAssembly.RuntimeError` or `RangeError`) whose message is exactly a known code name is a typed error.
 * Returns null for anything else, which means the module is dead.
 */
internal fun classifyJsError(thrown: JsAny?): MlsErrorCode? {
    val name = plainErrorMessage(thrown) ?: return null
    return MlsErrorCode.entries.firstOrNull { it.name == name }
}

/** The message of a plain `Error` (not a subclass), else null. */
@Suppress("UnusedParameter") // Read by the js() body, which detekt can't see.
private fun plainErrorMessage(thrown: JsAny?): String? =
    js("(thrown instanceof Error && Object.getPrototypeOf(thrown) === Error.prototype) ? thrown.message : null")

// Kotlin/Wasm ByteArrays live in the Wasm GC heap, which JS can't see: every crossing copies.
internal fun ByteArray.toUint8Array(): Uint8Array {
    val out = Uint8Array(size)
    for (i in indices) out[i] = this[i]
    return out
}

internal fun Uint8Array.toByteArray(): ByteArray = ByteArray(length) { this[it] }
