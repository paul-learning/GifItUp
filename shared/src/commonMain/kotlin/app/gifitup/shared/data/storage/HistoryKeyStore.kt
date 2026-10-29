package app.gifitup.shared.data.storage

/**
 * Where the 32-byte history key lives (ADR-0017): random, independent of the snapshot key, kept apart from the sealed
 * records it opens. The app wraps it with the same platform key as the snapshot key (`:mls`); the dev CLI keeps it in a
 * plain file (test accounts only). [save] is durable when it returns, keeps no reference to the array (the caller zeroes
 * it afterwards) and never replaces a stored key; [load] returns a copy the caller may zero.
 *
 * Unlike [SnapshotKeyStore], nothing here ever makes the wrapping key: a history key whose wrapping key is missing or
 * refuses is [LocalStateException.Problem.KEY_UNAVAILABLE], never a reason for a fresh key.
 */
interface HistoryKeyStore {
    suspend fun load(): ByteArray?

    suspend fun save(key: ByteArray)

    /** Removes the stored key, durably when it returns; only "reset history" and the discard of a registration call it. */
    suspend fun clear()
}
