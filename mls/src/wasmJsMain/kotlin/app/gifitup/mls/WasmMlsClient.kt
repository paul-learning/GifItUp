package app.gifitup.mls

import app.gifitup.shared.data.crypto.AuthPurpose
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
    ): MlsClient = withSnapshotKey(snapshotKey) { key -> WasmMlsClient(guarded { NativeMlsClient(username, key) }) }

    override fun restore(
        state: ByteArray,
        snapshotKey: ByteArray,
    ): MlsClient =
        withSnapshotKey(snapshotKey) { key ->
            val bytes = state.toUint8Array()
            WasmMlsClient(guarded { NativeMlsClient.restore(bytes, key) })
        }
}

/**
 * Hands [create] a JS copy of the key; the key's JS copy and our copy are both zeroed afterwards (Rust keeps its
 * own). [guarded] always returns, even after a trap, so the `finally` here runs (tested with a real trap).
 */
internal fun <T> withSnapshotKey(
    snapshotKey: ByteArray,
    create: (Uint8Array) -> T,
): T {
    val key = snapshotKey.toUint8Array()
    snapshotKey.fill(0)
    try {
        return create(key)
    } finally {
        for (i in 0 until key.length) key[i] = 0
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

    override fun fingerprint(signatureKey: ByteArray): ByteArray =
        call {
            val bytes = signatureKey.toUint8Array()
            guarded { native.fingerprint(bytes) }.toByteArray()
        }

    override fun exportState(): ByteArray = call { guarded { native.exportState() }.toByteArray() }

    override fun signAuth(
        purpose: AuthPurpose,
        challenge: ByteArray,
        origin: String,
        subject: String,
    ): ByteArray =
        call {
            val bytes = challenge.toUint8Array()
            guarded { native.signAuth(purpose.wireName(), bytes, origin, subject) }.toByteArray()
        }

    /** Never throws, like the JVM `close()`: if `free()` traps, the module is dead anyway (#66 SEC-10). */
    override fun close() {
        if (closed) return
        closed = true
        try {
            if (!WasmModule.dead) freeGuarded { native.free() }
        } catch (_: MlsException) {
            // The module is now marked dead; there is nothing left to free.
        }
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

/** The purpose name the crate's web binding takes (`AuthPurpose::from_name` in crypto/src/auth.rs). */
private fun AuthPurpose.wireName(): String =
    when (this) {
        AuthPurpose.Register -> "register"
        AuthPurpose.Login -> "login"
    }

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
