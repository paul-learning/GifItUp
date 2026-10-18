package app.gifitup.shared.data.storage

/**
 * This device's identity at rest: the encrypted MLS snapshot plus what it was registered as. [deviceId] is null while a
 * registration is pending (persisted, outcome not yet known). The snapshot key is not part of it: see
 * [SnapshotKeyStore]. The invite code is never part of it either (#79 SEC-5).
 */
class StoredIdentity(
    /** The canonical origin this identity belongs to; it is never used against another one. */
    val origin: String,
    val username: String,
    /** `MlsClient.exportState()`: already encrypted with the snapshot key. */
    val snapshot: ByteArray,
    val deviceId: String?,
) {
    override fun toString(): String = "StoredIdentity(origin=$origin, username=$username, deviceId=$deviceId)"
}

/**
 * Where the identity lives: a file for the dev CLI, platform storage in the app (#16). [save] is durable and atomic
 * when it returns (e.g. temp file + rename): a crash leaves either the old or the new identity, never a torn one.
 */
interface IdentityStore {
    suspend fun load(): StoredIdentity?

    suspend fun save(identity: StoredIdentity)

    /** Removes the identity; only for a pending registration the server definitely refused (#95 CR-2). */
    suspend fun clear()
}

/**
 * Where the 32-byte snapshot key lives, kept apart from the snapshot (#79 SEC-12). Protecting it is the
 * implementation's job: the app wraps it with the Android Keystore / WebCrypto (ADR-0007, #16); the dev CLI keeps it in
 * a plain file, which is acceptable for test accounts only. [save] is durable when it returns and keeps no reference to
 * the array (the caller zeroes it afterwards); [load] returns a copy the caller may zero.
 */
interface SnapshotKeyStore {
    suspend fun load(): ByteArray?

    suspend fun save(key: ByteArray)

    suspend fun clear()
}
