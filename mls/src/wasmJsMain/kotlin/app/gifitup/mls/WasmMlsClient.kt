package app.gifitup.mls

import app.gifitup.shared.data.crypto.AuthPurpose
import app.gifitup.shared.data.crypto.CreatedKeyPackage
import app.gifitup.shared.data.crypto.EncryptedMessage
import app.gifitup.shared.data.crypto.IncomingMessage
import app.gifitup.shared.data.crypto.LeafInfo
import app.gifitup.shared.data.crypto.MlsClient
import app.gifitup.shared.data.crypto.MlsClientFactory
import app.gifitup.shared.data.crypto.MlsErrorCode
import app.gifitup.shared.data.crypto.MlsException
import app.gifitup.shared.data.crypto.PreparedCommit
import app.gifitup.shared.data.crypto.PublicIdentity
import app.gifitup.shared.data.crypto.WelcomeInfo
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
    loadWasmModule()
    return WasmMlsClientFactory
}

/** Loads the wasm module (once, shared by every caller) and returns when it is ready. */
internal suspend fun loadWasmModule() {
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
}

internal object WasmModule {
    var loading: kotlin.js.Promise<JsAny?>? = null
    var dead = false
}

private object WasmMlsClientFactory : MlsClientFactory {
    override fun create(
        username: String,
        snapshotKey: ByteArray,
    ): MlsClient = withKeyCopy(snapshotKey) { key -> WasmMlsClient(guarded { NativeMlsClient(username, key) }) }

    override fun restore(
        state: ByteArray,
        snapshotKey: ByteArray,
    ): MlsClient =
        withKeyCopy(snapshotKey) { key ->
            val bytes = state.toUint8Array()
            WasmMlsClient(guarded { NativeMlsClient.restore(bytes, key) })
        }
}

/**
 * Hands [create] a JS copy of a secret key (the snapshot key or the history key); the key's JS copy and our copy are
 * both zeroed afterwards (Rust keeps its own). [guarded] always returns, even after a trap, so the `finally` here runs
 * (tested with a real trap).
 */
internal fun <T> withKeyCopy(
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
@Suppress("TooManyFunctions") // One method per MlsClient operation; the interface is the API (ADR-0007).
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

    override fun rekeyAndExport(newKey: ByteArray): ByteArray =
        call {
            val key = newKey.toUint8Array()
            newKey.fill(0)
            try {
                guarded { native.rekeyAndExport(key) }.toByteArray()
            } finally {
                for (i in 0 until key.length) key[i] = 0
            }
        }

    override fun exportsUnderKey(): Long = call { guarded { native.exportsUnderKey().toJsNumber() }.toDouble().toLong() }

    override fun setAppState(state: ByteArray) = call { guardedUnit { native.setAppState(state.toUint8Array()) } }

    override fun appState(): ByteArray = call { guarded { native.appState() }.toByteArray() }

    override fun createGroup(): ByteArray = call { guarded { native.createGroup() }.toByteArray() }

    override fun prepareAdd(
        groupId: ByteArray,
        keyPackages: List<ByteArray>,
    ): PreparedCommit =
        call {
            val id = groupId.toUint8Array()
            val packed = keyPackages.toLengthPrefixed().toUint8Array()
            guarded { native.prepareAdd(id, packed) }.toSharedAndFree()
        }

    override fun prepareRemove(
        groupId: ByteArray,
        leafIndexes: List<Int>,
    ): PreparedCommit =
        call {
            val id = groupId.toUint8Array()
            val packed = leafIndexes.toBigEndian().toUint8Array()
            guarded { native.prepareRemove(id, packed) }.toSharedAndFree()
        }

    override fun prepareUpdate(groupId: ByteArray): PreparedCommit =
        call {
            val id = groupId.toUint8Array()
            guarded { native.prepareUpdate(id) }.toSharedAndFree()
        }

    override fun mergePending(groupId: ByteArray): Long =
        call {
            val id = groupId.toUint8Array()
            guarded { native.mergePending(id).toJsNumber() }.toDouble().toLong()
        }

    override fun clearPending(groupId: ByteArray) =
        call {
            val id = groupId.toUint8Array()
            guardedUnit { native.clearPending(id) }
        }

    override fun hasPendingCommit(groupId: ByteArray): Boolean =
        call {
            val id = groupId.toUint8Array()
            guarded { native.hasPendingCommit(id).toJsBoolean() }.toBoolean()
        }

    override fun encrypt(
        groupId: ByteArray,
        plaintext: ByteArray,
    ): EncryptedMessage =
        call {
            val id = groupId.toUint8Array()
            val bytes = plaintext.toUint8Array()
            guarded { native.encrypt(id, bytes) }.toSharedAndFree()
        }

    override fun stageIncoming(
        groupId: ByteArray,
        message: ByteArray,
    ): IncomingMessage =
        call {
            val id = groupId.toUint8Array()
            val bytes = message.toUint8Array()
            guarded { native.stageIncoming(id, bytes) }.toSharedAndFree()
        }

    override fun mergeStaged(token: Int): Long = call { guarded { native.mergeStaged(token).toJsNumber() }.toDouble().toLong() }

    override fun discardStaged(token: Int) = call { guardedUnit { native.discardStaged(token) } }

    override fun inspectWelcome(
        welcome: ByteArray,
        expectedGroupId: ByteArray,
    ): WelcomeInfo =
        call {
            val bytes = welcome.toUint8Array()
            val id = expectedGroupId.toUint8Array()
            guarded { native.inspectWelcome(bytes, id) }.toSharedAndFree()
        }

    override fun joinWelcome(token: Int): ByteArray = call { guarded { native.joinWelcome(token) }.toByteArray() }

    override fun discardWelcome(token: Int) = call { guardedUnit { native.discardWelcome(token) } }

    override fun forgetGroup(groupId: ByteArray) =
        call {
            val id = groupId.toUint8Array()
            guardedUnit { native.forgetGroup(id) }
        }

    override fun members(groupId: ByteArray): List<LeafInfo> =
        call {
            val id = groupId.toUint8Array()
            guarded { native.members(id) }.toSharedAndFree()
        }

    override fun memberCount(groupId: ByteArray): Int =
        call {
            val id = groupId.toUint8Array()
            guarded { native.memberCount(id).toJsNumber() }.toInt()
        }

    override fun epoch(groupId: ByteArray): Long =
        call {
            val id = groupId.toUint8Array()
            guarded { native.epoch(id).toJsNumber() }.toDouble().toLong()
        }

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
internal fun freeGuarded(free: () -> Unit) {
    if (WasmModule.dead) return
    guarded<JsAny?> {
        free()
        null
    }
}
