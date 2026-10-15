package app.gifitup.mls

import app.gifitup.shared.data.crypto.CreatedKeyPackage
import app.gifitup.shared.data.crypto.MlsClient
import app.gifitup.shared.data.crypto.MlsClientFactory
import app.gifitup.shared.data.crypto.MlsErrorCode
import app.gifitup.shared.data.crypto.MlsException
import app.gifitup.shared.data.crypto.PublicIdentity
import kotlinx.coroutines.await
import org.khronos.webgl.Uint8Array
import org.khronos.webgl.get
import org.khronos.webgl.set

/**
 * Loads the wasm module once and returns the browser [MlsClientFactory] (ADR-0007). After a trap the module
 * is dead for good: every client and the factory refuse all calls, and the app reloads the page and
 * restores from the last persisted snapshot.
 */
suspend fun loadWasmMlsClientFactory(): MlsClientFactory {
    // Concurrent first callers share one load instead of instantiating the module twice (#66 CR-4).
    val loading = WasmModule.loading ?: initCrypto().also { WasmModule.loading = it }
    var loaded = false
    try {
        loading.await<JsAny?>()
        loaded = true
    } finally {
        // A failed load (e.g. a network error fetching the .wasm) is retried on the next call (#66 SEC-9).
        if (!loaded) WasmModule.loading = null
    }
    return WasmMlsClientFactory
}

internal object WasmModule {
    var loading: kotlin.js.Promise<JsAny?>? = null
    var dead = false
}

private object WasmMlsClientFactory : MlsClientFactory {
    override fun create(
        username: String,
        snapshotKey: ByteArray,
    ): MlsClient = withKey(snapshotKey) { key -> WasmMlsClient(guarded { NativeMlsClient(username, key) }) }

    override fun restore(
        state: ByteArray,
        snapshotKey: ByteArray,
    ): MlsClient =
        withKey(snapshotKey) { key ->
            val bytes = state.toUint8Array()
            WasmMlsClient(guarded { NativeMlsClient.restore(bytes, key) })
        }

    /**
     * The key's JS copy and our copy are both zeroed after the call (Rust keeps its own). [guarded] always
     * returns, even after a trap, so the `finally` here runs.
     */
    private fun withKey(
        snapshotKey: ByteArray,
        create: (Uint8Array) -> MlsClient,
    ): MlsClient {
        val key = snapshotKey.toUint8Array()
        snapshotKey.fill(0)
        try {
            return create(key)
        } finally {
            for (i in 0 until key.length) key[i] = 0
        }
    }
}

/** [MlsClient] over one wasm-bindgen `NativeMlsClient`; frees it in [close]. */
private class WasmMlsClient(
    private val native: NativeMlsClient,
) : MlsClient {
    private var closed = false

    override fun publicIdentity(): PublicIdentity = call { guarded { native.publicIdentity() }.toShared() }

    override fun createKeyPackages(count: Int): List<CreatedKeyPackage> =
        call {
            val created = guarded { native.createKeyPackages(count) }
            val items = List(created.length) { i -> checkNotNull(created[i]) }
            try {
                items.map { kp ->
                    CreatedKeyPackage(
                        guarded { kp.keyPackage }.toByteArray(),
                        guarded { kp.keyPackageRef }.toByteArray(),
                        guarded { kp.notAfter.toJsNumber() }.toDouble().toLong(),
                    )
                }
            } finally {
                // Every wasm-side object is freed, also when one conversion fails (#66 CR-5).
                items.forEach { kp -> freeGuarded { kp.free() } }
            }
        }

    override fun pruneExpiredKeyPackages(): Int = call { guarded { native.pruneExpiredKeyPackages().toJsNumber() }.toInt() }

    override fun inspectKeyPackage(
        keyPackage: ByteArray,
        expectedUsername: String,
    ): PublicIdentity =
        call {
            val bytes = keyPackage.toUint8Array()
            guarded { native.inspectKeyPackage(bytes, expectedUsername) }.toShared()
        }

    override fun exportState(): ByteArray = call { guarded { native.exportState() }.toByteArray() }

    override fun close() {
        if (!closed && !WasmModule.dead) freeGuarded { native.free() }
        closed = true
    }

    private fun <T> call(block: () -> T): T {
        if (closed) throw MlsException(MlsErrorCode.Poisoned)
        return kotlinErrorsAsInternal(block)
    }
}

/**
 * Only [MlsException] leaves this module: a Kotlin exception in our own conversion code is a bug, reported as
 * [MlsErrorCode.Internal] without message or cause (#66 CR-5). Wasm errors arrive already translated by [guarded].
 */
@Suppress("TooGenericExceptionCaught", "SwallowedException")
internal fun <T> kotlinErrorsAsInternal(block: () -> T): T =
    try {
        block()
    } catch (e: MlsException) {
        throw e
    } catch (_: Throwable) {
        throw MlsException(MlsErrorCode.Internal)
    }

/** What [jsTry] returns: the call's value, or what it threw. */
private external interface JsTryResult : JsAny {
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
private fun jsTry(block: () -> JsAny?): JsTryResult =
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

private fun JsPublicIdentity.toShared(): PublicIdentity {
    try {
        return PublicIdentity(guarded { username.toJsString() }.toString(), guarded { signaturePublicKey }.toByteArray())
    } finally {
        freeGuarded { free() }
    }
}

/** `free()` goes through [guarded] too; after a trap the module is dead and nothing is freed any more. */
private fun freeGuarded(free: () -> Unit) {
    if (WasmModule.dead) return
    guarded<JsAny?> {
        free()
        null
    }
}

// Kotlin/Wasm ByteArrays live in the Wasm GC heap, which JS can't see: every crossing copies.
private fun ByteArray.toUint8Array(): Uint8Array {
    val out = Uint8Array(size)
    for (i in indices) out[i] = this[i]
    return out
}

private fun Uint8Array.toByteArray(): ByteArray = ByteArray(length) { this[it] }
