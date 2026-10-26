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
import uniffi.gifitup_crypto.ErrorCode
import uniffi.gifitup_crypto.NativeMlsClient
import uniffi.gifitup_crypto.AuthPurpose as NativeAuthPurpose
import uniffi.gifitup_crypto.EncryptedMessage as NativeEncryptedMessage
import uniffi.gifitup_crypto.IncomingMessage as NativeIncomingMessage
import uniffi.gifitup_crypto.LeafInfo as NativeLeafInfo
import uniffi.gifitup_crypto.PreparedCommit as NativePreparedCommit
import uniffi.gifitup_crypto.WelcomeInfo as NativeWelcomeInfo

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
@Suppress("TooManyFunctions") // One method per MlsClient operation; the interface is the API (ADR-0007).
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

    override fun fingerprint(signatureKey: ByteArray): ByteArray = call { native.fingerprint(signatureKey) }

    override fun exportState(): ByteArray = call { native.exportState() }

    override fun rekeyAndExport(newKey: ByteArray): ByteArray =
        try {
            call { native.rekeyAndExport(newKey) }
        } finally {
            newKey.fill(0)
        }

    override fun exportsUnderKey(): Long = call { native.exportsUnderKey().toLong() }

    override fun setAppState(state: ByteArray) = call { native.setAppState(state) }

    override fun appState(): ByteArray = call { native.appState() }

    override fun createGroup(): ByteArray = call { native.createGroup() }

    override fun prepareAdd(
        groupId: ByteArray,
        keyPackages: List<ByteArray>,
    ): PreparedCommit = call { native.prepareAdd(groupId, keyPackages).toShared() }

    override fun prepareRemove(
        groupId: ByteArray,
        leafIndexes: List<Int>,
    ): PreparedCommit =
        call {
            if (leafIndexes.any { it < 0 }) throw MlsException(MlsErrorCode.InvalidInput)
            native.prepareRemove(groupId, leafIndexes.map { it.toUInt() }).toShared()
        }

    override fun prepareUpdate(groupId: ByteArray): PreparedCommit = call { native.prepareUpdate(groupId).toShared() }

    override fun mergePending(groupId: ByteArray): Long = call { native.mergePending(groupId).toLong() }

    override fun clearPending(groupId: ByteArray) = call { native.clearPending(groupId) }

    override fun hasPendingCommit(groupId: ByteArray): Boolean = call { native.hasPendingCommit(groupId) }

    override fun encrypt(
        groupId: ByteArray,
        plaintext: ByteArray,
    ): EncryptedMessage = call { native.encrypt(groupId, plaintext).toShared() }

    override fun stageIncoming(
        groupId: ByteArray,
        message: ByteArray,
    ): IncomingMessage = call { native.stageIncoming(groupId, message).toShared() }

    override fun mergeStaged(token: Int): Long = call { native.mergeStaged(token.toUInt()).toLong() }

    override fun discardStaged(token: Int) = call { native.discardStaged(token.toUInt()) }

    override fun inspectWelcome(
        welcome: ByteArray,
        expectedGroupId: ByteArray,
    ): WelcomeInfo = call { native.inspectWelcome(welcome, expectedGroupId).toShared() }

    override fun joinWelcome(token: Int): ByteArray = call { native.joinWelcome(token.toUInt()) }

    override fun discardWelcome(token: Int) = call { native.discardWelcome(token.toUInt()) }

    override fun forgetGroup(groupId: ByteArray) = call { native.forgetGroup(groupId) }

    override fun members(groupId: ByteArray): List<LeafInfo> = call { native.members(groupId).map { it.toShared() } }

    override fun memberCount(groupId: ByteArray): Int = call { native.memberCount(groupId).toInt() }

    override fun epoch(groupId: ByteArray): Long = call { native.epoch(groupId).toLong() }

    override fun signAuth(
        purpose: AuthPurpose,
        challenge: ByteArray,
        origin: String,
        subject: String,
    ): ByteArray = call { native.signAuth(purpose.toNative(), challenge, origin, subject) }

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

@Suppress("CyclomaticComplexMethod") // One branch per closed error code; splitting would hide the exhaustive `when`.
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
        is ErrorCode.PendingOperation -> MlsErrorCode.PendingOperation
        is ErrorCode.WrongEpoch -> MlsErrorCode.WrongEpoch
        is ErrorCode.Replay -> MlsErrorCode.Replay
        is ErrorCode.Refused -> MlsErrorCode.Refused
        is ErrorCode.LimitExceeded -> MlsErrorCode.LimitExceeded
        is ErrorCode.UnknownToken -> MlsErrorCode.UnknownToken
        is ErrorCode.Poisoned -> MlsErrorCode.Poisoned
        is ErrorCode.Internal -> MlsErrorCode.Internal
    }

private fun NativeLeafInfo.toShared() = LeafInfo(username, signatureKey, leafIndex.toInt())

private fun NativePreparedCommit.toShared() =
    PreparedCommit(commit, welcome, epoch.toLong(), added.map { it.toShared() }, removed.map { it.toShared() })

private fun NativeEncryptedMessage.toShared() = EncryptedMessage(message, messageRef)

private fun NativeIncomingMessage.toShared(): IncomingMessage =
    if (isCommit) {
        IncomingMessage.Commit(
            token.toInt(),
            sender.toShared(),
            epoch.toLong(),
            added.map { it.toShared() },
            removed.map { it.toShared() },
            selfRemoved,
        )
    } else {
        IncomingMessage.Application(plaintext, sender.toShared(), messageRef)
    }

private fun NativeWelcomeInfo.toShared() = WelcomeInfo(token.toInt(), groupId, sender.toShared(), members.map { it.toShared() })

private fun uniffi.gifitup_crypto.PublicIdentity.toShared() = PublicIdentity(username, signaturePublicKey)

private fun AuthPurpose.toNative(): NativeAuthPurpose =
    when (this) {
        AuthPurpose.Register -> NativeAuthPurpose.REGISTER
        AuthPurpose.Login -> NativeAuthPurpose.LOGIN
    }
