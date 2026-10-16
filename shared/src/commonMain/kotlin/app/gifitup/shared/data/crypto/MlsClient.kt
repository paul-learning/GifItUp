package app.gifitup.shared.data.crypto

/**
 * The only way Kotlin code reaches the MLS core in `crypto/` (ADR-0007). Implementations live in
 * `:mls` (uniffi on Android/JVM, wasm-bindgen on the web) and are built in the composition roots.
 *
 * v1 covers identity, KeyPackages, the encrypted state snapshot and signing the server's auth challenge (ADR-0009);
 * group operations come with #22.
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
