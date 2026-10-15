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
    if (!WasmModule.initialized) {
        initCrypto().await<JsAny?>()
        WasmModule.initialized = true
    }
    return WasmMlsClientFactory
}

internal object WasmModule {
    var initialized = false
    var dead = false
}

private object WasmMlsClientFactory : MlsClientFactory {
    override fun create(
        username: String,
        snapshotKey: ByteArray,
    ): MlsClient = withKey(snapshotKey) { key -> WasmMlsClient(NativeMlsClient(username, key)) }

    override fun restore(
        state: ByteArray,
        snapshotKey: ByteArray,
    ): MlsClient = withKey(snapshotKey) { key -> WasmMlsClient(NativeMlsClient.restore(state.toUint8Array(), key)) }

    /** The key's JS copy and our copy are both zeroed after the call (Rust keeps its own). */
    private fun withKey(
        snapshotKey: ByteArray,
        create: (Uint8Array) -> MlsClient,
    ): MlsClient {
        val key = snapshotKey.toUint8Array()
        snapshotKey.fill(0)
        return try {
            translated { create(key) }
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

    override fun publicIdentity(): PublicIdentity = call { native.publicIdentity().toShared() }

    override fun createKeyPackages(count: Int): List<CreatedKeyPackage> =
        call {
            val created = native.createKeyPackages(count)
            List(created.length) { i ->
                val kp = checkNotNull(created[i])
                CreatedKeyPackage(kp.keyPackage.toByteArray(), kp.keyPackageRef.toByteArray(), kp.notAfter.toLong())
                    .also { kp.free() }
            }
        }

    override fun pruneExpiredKeyPackages(): Int = call { native.pruneExpiredKeyPackages() }

    override fun inspectKeyPackage(
        keyPackage: ByteArray,
        expectedUsername: String,
    ): PublicIdentity = call { native.inspectKeyPackage(keyPackage.toUint8Array(), expectedUsername).toShared() }

    override fun exportState(): ByteArray = call { native.exportState().toByteArray() }

    override fun close() {
        if (!closed && !WasmModule.dead) native.free()
        closed = true
    }

    private fun <T> call(block: () -> T): T {
        if (closed) throw MlsException(MlsErrorCode.Poisoned)
        return translated(block)
    }
}

/**
 * Default-deny error classification by JS type (ADR-0007): only a plain `Error` whose message is exactly a
 * known code name is a typed error. Anything else (`WebAssembly.RuntimeError` from a trap, `RangeError`,
 * unknown messages) marks the whole module dead.
 */
private fun <T> translated(block: () -> T): T {
    if (WasmModule.dead) throw MlsException(MlsErrorCode.Poisoned)
    return try {
        block()
    } catch (e: JsException) {
        val code = errorCodeName(e.thrownValue)?.let { name -> MlsErrorCode.entries.firstOrNull { it.name == name } }
        if (code == null || code == MlsErrorCode.Poisoned) WasmModule.dead = true
        throw MlsException(code ?: MlsErrorCode.Poisoned)
    }
}

/** The message of a plain `Error` (not a subclass), else null. */
@Suppress("UnusedParameter") // Read by the js() body, which detekt can't see.
private fun errorCodeName(thrown: JsAny?): String? =
    js("(thrown instanceof Error && Object.getPrototypeOf(thrown) === Error.prototype) ? thrown.message : null")

private fun JsPublicIdentity.toShared(): PublicIdentity = PublicIdentity(username, signaturePublicKey.toByteArray()).also { free() }

// Kotlin/Wasm ByteArrays live in the Wasm GC heap, which JS can't see: every crossing copies.
private fun ByteArray.toUint8Array(): Uint8Array {
    val out = Uint8Array(size)
    for (i in indices) out[i] = this[i]
    return out
}

private fun Uint8Array.toByteArray(): ByteArray = ByteArray(length) { this[it] }
