package app.gifitup.shared.data.crypto

/**
 * The only way Kotlin code reaches the MLS core in `crypto/` (ADR-0007). Implementations live in
 * `:mls` (uniffi on Android/JVM, wasm-bindgen on the web) and are built in the composition roots.
 *
 * Covers identity, KeyPackages, the encrypted state snapshot, signing the server's auth challenge (ADR-0009) and the
 * group lifecycle (ADR-0016): our own commits are *prepared* and then merged or cleared, incoming commits and welcomes
 * are *staged* and then merged/joined or discarded. [exportState] is refused ([MlsErrorCode.PendingOperation]) while
 * anything is staged.
 * Every method throws [MlsException] on failure. After [MlsErrorCode.Poisoned] the client is dead:
 * close it and restore from the last persisted snapshot, never export its state. Calling a closed client is a
 * programming error and also throws [MlsErrorCode.Poisoned].
 */
@Suppress("TooManyFunctions") // One method per crate operation; the interface is the whole MLS boundary (ADR-0007).
interface MlsClient : AutoCloseable {
    fun publicIdentity(): PublicIdentity

    /**
     * Creates 1..100 single-use KeyPackages for upload. At most 200 unused ones are held: when the new ones would exceed
     * that, the oldest held ones are dropped first (their private keys deleted, #121), so a refill never blocks. A Welcome
     * for a dropped KeyPackage can no longer be processed, so pending Welcomes are processed before a refill.
     */
    fun createKeyPackages(count: Int): List<CreatedKeyPackage>

    /** Deletes the private keys of expired own KeyPackages; returns how many were deleted. */
    fun pruneExpiredKeyPackages(): Int

    /**
     * Checks someone else's KeyPackage. [expectedUsername] comes from the untrusted server, so this
     * is a consistency check; the TOFU record of the signature key (#18) authenticates.
     */
    fun inspectKeyPackage(
        keyPackage: ByteArray,
        expectedUsername: String,
    ): PublicIdentity

    /**
     * The fingerprint of a signature public key (ADR-0014): [FingerprintEmojis.LENGTH] bytes, each an index into
     * [FingerprintEmojis]. [signatureKey] must be exactly 32 bytes ([MlsErrorCode.InvalidInput] otherwise). It reads no
     * client state, but like every call it is refused on a closed or poisoned client. A user compares **all** of it, out
     * of band, never part of it.
     */
    fun fingerprint(signatureKey: ByteArray): ByteArray

    /** The whole client state, encrypted with the snapshot key given at create/restore. */
    fun exportState(): ByteArray

    /**
     * Exports under [newKey] (32 bytes, zeroed after use) and, only if that succeeded, switches to it and restarts the
     * export count (ADR-0011). On any error the old key stays. Store the returned snapshot with the new key wrapped,
     * and only then drop the old key. The same key again is [MlsErrorCode.InvalidInput].
     */
    fun rekeyAndExport(newKey: ByteArray): ByteArray

    /** Exports made under the current key; further exports are refused ([MlsErrorCode.LimitExceeded]) at 2^24. */
    fun exportsUnderKey(): Long

    /** The sync engine's opaque state (at most 256 KiB), exported inside the next snapshot (ADR-0016). */
    fun setAppState(state: ByteArray)

    fun appState(): ByteArray

    /** Creates a group with this client as the only member; returns its MLS group id (16 bytes). */
    fun createGroup(): ByteArray

    /** Prepares a commit adding the owners of 1..10 [keyPackages]; they are validated again here. */
    fun prepareAdd(
        groupId: ByteArray,
        keyPackages: List<ByteArray>,
    ): PreparedCommit

    /** Prepares a commit removing 1..10 members by leaf index (never our own). */
    fun prepareRemove(
        groupId: ByteArray,
        leafIndexes: List<Int>,
    ): PreparedCommit

    /** Prepares a self-update commit (same name and key, fresh path secrets). */
    fun prepareUpdate(groupId: ByteArray): PreparedCommit

    /** Applies the pending commit after the Delivery Service accepted it; returns the new epoch. */
    fun mergePending(groupId: ByteArray): Long

    /** Drops the pending commit after a rejection, wiping its secrets. */
    fun clearPending(groupId: ByteArray)

    fun hasPendingCommit(groupId: ByteArray): Boolean

    /** Seals at most 896 bytes for the group (padded to one block, ADR-0006 B7). */
    fun encrypt(
        groupId: ByteArray,
        plaintext: ByteArray,
    ): EncryptedMessage

    /**
     * Processes an incoming message of the group. Staging is destructive (the message cannot be processed again), so
     * decide on a staged commit right away, locally; nothing is exported meanwhile.
     */
    fun stageIncoming(
        groupId: ByteArray,
        message: ByteArray,
    ): IncomingMessage

    /** Applies the staged commit; returns the new epoch. */
    fun mergeStaged(token: Int): Long

    fun discardStaged(token: Int)

    /** Parses a welcome fetched for [expectedGroupId]; joins nothing and never overwrites an existing group. */
    fun inspectWelcome(
        welcome: ByteArray,
        expectedGroupId: ByteArray,
    ): WelcomeInfo

    /** Joins the inspected welcome; returns the group id. */
    fun joinWelcome(token: Int): ByteArray

    fun discardWelcome(token: Int)

    /** Deletes the group and everything staged for its id. */
    fun forgetGroup(groupId: ByteArray)

    /** The leaves as the tree has them now, ghosts included. */
    fun members(groupId: ByteArray): List<LeafInfo>

    fun memberCount(groupId: ByteArray): Int

    fun epoch(groupId: ByteArray): Long

    /**
     * Signs the server's registration or login challenge with this device's signature key (ADR-0009). The crate
     * builds the signed bytes itself; nothing else can be signed.
     * - [challenge]: the server's challenge as received (16..64 bytes, opaque).
     * - [origin]: the server origin the app is configured with, never one the server sent; canonical form only
     *   (`https://` + lowercase host + optional non-443 port, nothing after it).
     * - [subject]: for [AuthPurpose.Register] exactly this client's username, for [AuthPurpose.Login] the
     *   server-assigned device id as a lowercase UUID.
     *
     * Returns the 64-byte Ed25519 signature; a rule violation throws [MlsErrorCode.InvalidInput].
     */
    fun signAuth(
        purpose: AuthPurpose,
        challenge: ByteArray,
        origin: String,
        subject: String,
    ): ByteArray
}

/** Creates or restores [MlsClient]s. Both functions zero [snapshotKey] (32 bytes) after use. */
interface MlsClientFactory {
    fun create(
        username: String,
        snapshotKey: ByteArray,
    ): MlsClient

    fun restore(
        state: ByteArray,
        snapshotKey: ByteArray,
    ): MlsClient
}
