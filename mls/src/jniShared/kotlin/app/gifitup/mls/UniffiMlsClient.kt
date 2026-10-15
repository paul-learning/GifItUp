package app.gifitup.mls

import app.gifitup.shared.data.crypto.CreatedKeyPackage
import app.gifitup.shared.data.crypto.MlsClient
import app.gifitup.shared.data.crypto.MlsClientFactory
import app.gifitup.shared.data.crypto.MlsErrorCode
import app.gifitup.shared.data.crypto.MlsException
import app.gifitup.shared.data.crypto.PublicIdentity
import uniffi.gifitup_crypto.ErrorCode
import uniffi.gifitup_crypto.NativeMlsClient

/** [MlsClientFactory] over the uniffi bindings (Android and JVM; ADR-0007). */
object UniffiMlsClientFactory : MlsClientFactory {
    override fun create(
        username: String,
        snapshotKey: ByteArray,
    ): MlsClient = wrapping(snapshotKey) { UniffiMlsClient(NativeMlsClient(username, snapshotKey)) }

    override fun restore(
        state: ByteArray,
        snapshotKey: ByteArray,
    ): MlsClient = wrapping(snapshotKey) { UniffiMlsClient(NativeMlsClient.restore(state, snapshotKey)) }

    /** Rust copies the key; our copy is zeroed whatever happens. */
    private inline fun wrapping(
        snapshotKey: ByteArray,
        create: () -> MlsClient,
    ): MlsClient =
        try {
            translated(create)
        } finally {
            snapshotKey.fill(0)
        }
}

/**
 * [MlsClient] over one uniffi `NativeMlsClient`. It owns the native object and frees it in [close].
 * Errors arrive as closed codes; anything else from the bindings means the native side is in an unknown
 * state, so this client refuses every later call (ADR-0007).
 */
internal class UniffiMlsClient(
    private val native: NativeMlsClient,
) : MlsClient {
    // Calls may come from different threads; Rust serializes them, this flag must be visible to all (#66 SEC-5).
    @Volatile
    private var dead = false

    override fun publicIdentity(): PublicIdentity = call { native.publicIdentity().toShared() }

    override fun createKeyPackages(count: Int): List<CreatedKeyPackage> =
        call {
            if (count < 1) throw MlsException(MlsErrorCode.InvalidInput)
            native.createKeyPackages(count.toUInt()).map {
                CreatedKeyPackage(it.keyPackage, it.keyPackageRef, it.notAfter.toLong())
            }
        }

    override fun pruneExpiredKeyPackages(): Int = call { native.pruneExpiredKeyPackages().toInt() }

    override fun inspectKeyPackage(
        keyPackage: ByteArray,
        expectedUsername: String,
    ): PublicIdentity = call { native.inspectKeyPackage(keyPackage, expectedUsername).toShared() }

    override fun exportState(): ByteArray = call { native.exportState() }

    override fun close() {
        dead = true
        native.close()
    }

    private inline fun <T> call(block: () -> T): T {
        if (dead) throw MlsException(MlsErrorCode.Poisoned)
        return try {
            translated(block)
        } catch (e: MlsException) {
            if (e.code == MlsErrorCode.Poisoned) dead = true
            throw e
        }
    }
}

/**
 * Maps the generated error type to [MlsException] by type, not by class name, so R8 renaming can't break
 * it. Any other Throwable (e.g. uniffi's InternalException) becomes [MlsErrorCode.Poisoned] with no
 * message and no cause. Catching everything and dropping the original exception is deliberate: nothing
 * from the native side may escape untranslated, and no input or internal state may reach logs (ADR-0007).
 */
@Suppress("TooGenericExceptionCaught", "SwallowedException")
internal inline fun <T> translated(block: () -> T): T =
    try {
        block()
    } catch (e: MlsException) {
        throw e
    } catch (e: ErrorCode) {
        throw MlsException(e.toShared())
    } catch (_: Throwable) {
        throw MlsException(MlsErrorCode.Poisoned)
    }

private fun ErrorCode.toShared(): MlsErrorCode =
    when (this) {
        is ErrorCode.InvalidInput -> MlsErrorCode.InvalidInput
        is ErrorCode.InvalidUsername -> MlsErrorCode.InvalidUsername
        is ErrorCode.InvalidKeyPackage -> MlsErrorCode.InvalidKeyPackage
        is ErrorCode.UnknownGroup -> MlsErrorCode.UnknownGroup
        is ErrorCode.Mls -> MlsErrorCode.Mls
        is ErrorCode.SnapshotInvalid -> MlsErrorCode.SnapshotInvalid
        is ErrorCode.SnapshotVersion -> MlsErrorCode.SnapshotVersion
        is ErrorCode.SnapshotAuth -> MlsErrorCode.SnapshotAuth
        is ErrorCode.Poisoned -> MlsErrorCode.Poisoned
        is ErrorCode.Internal -> MlsErrorCode.Internal
    }

private fun uniffi.gifitup_crypto.PublicIdentity.toShared() = PublicIdentity(username, signaturePublicKey)
