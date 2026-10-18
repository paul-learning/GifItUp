package app.gifitup.shared.data.storage

/**
 * The app's local key-value store (ADR-0011): an app-private no-backup file on Android, IndexedDB on the web. It holds
 * the identity record, the wrapped snapshot key and the restore-failure counter, and nothing that isn't encrypted or
 * public: the snapshot is encrypted inside Rust (ADR-0007) and the key is wrapped by the platform (`:mls`).
 *
 * [putAll] is atomic and durable when it returns: after a crash all of its entries are old or all are new. It takes
 * several entries so a later caller can write the snapshot together with other state in one transaction (#24, #92
 * SEC-11). One instance per device: the platform makes sure only one app instance uses the store (ADR-0011).
 */
interface LocalStore {
    suspend fun get(key: String): ByteArray?

    /** Writes every entry at once; a `null` value deletes its key. */
    suspend fun putAll(entries: Map<String, ByteArray?>)
}

/**
 * What a store found instead of usable state. Thrown by the app's [IdentityStore] and [SnapshotKeyStore] (the dev
 * CLI's file stores report their own errors), and mapped to a closed `AuthError` by the use cases. Carries no message.
 */
class LocalStateException(
    val problem: Problem,
) : Exception() {
    enum class Problem {
        /** A record exists but can't be read: wrong version, truncated, or not ours. */
        DAMAGED,

        /**
         * A wrapped snapshot key exists, but the platform key that unwraps it doesn't, or refused (ADR-0011, #16
         * SEC-5). Never a reason to make a new key or to count a failed restore (#16 SEC-4): it can be transient.
         */
        KEY_UNAVAILABLE,
    }

    override fun toString(): String = "LocalStateException(problem=$problem)"
}
