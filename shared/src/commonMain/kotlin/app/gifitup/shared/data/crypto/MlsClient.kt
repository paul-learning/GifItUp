package app.gifitup.shared.data.crypto

/**
 * The only way Kotlin code reaches the MLS core in `crypto/` (ADR-0007). Implementations live in
 * `:mls` (uniffi on Android/JVM, wasm-bindgen on the web) and are built in the composition roots.
 *
 * v1 covers identity, KeyPackages and the encrypted state snapshot; group operations come with #22.
 * Every method throws [MlsException] on failure. After [MlsErrorCode.Poisoned] the client is dead:
 * close it and restore from the last persisted snapshot, never export its state. Calling a closed client is a
 * programming error and also throws [MlsErrorCode.Poisoned].
 */
interface MlsClient : AutoCloseable {
    fun publicIdentity(): PublicIdentity

    /** Creates 1..100 single-use KeyPackages for upload (at most 200 unused ones are held). */
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

    /** The whole client state, encrypted with the snapshot key given at create/restore. */
    fun exportState(): ByteArray
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
