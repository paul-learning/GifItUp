package app.gifitup.shared.domain.auth

import app.gifitup.shared.data.crypto.SecureRandomSource
import app.gifitup.shared.data.storage.HistoryKeyStore
import app.gifitup.shared.data.storage.IdentityStore
import app.gifitup.shared.data.storage.LocalStateException
import app.gifitup.shared.data.storage.LocalStateException.Problem.KEY_UNAVAILABLE
import app.gifitup.shared.data.storage.LocalStateException.Problem.STORAGE
import app.gifitup.shared.data.storage.RecordStore
import app.gifitup.shared.data.storage.RecordStoreException

/**
 * The history key of this device (ADR-0017), made at most once and **never replaced**:
 *
 * - A stored key is returned. One that doesn't open, is damaged or whose wrapping key is gone is thrown as it is
 *   (`KEY_UNAVAILABLE`, `DAMAGED`, `STORAGE`), never answered with a fresh key.
 * - No key and a record store that positively holds nothing: 32 fresh random bytes are stored and returned. This is the
 *   new identity and the identity that existed before history did.
 * - No key but history records (or anything in the record store, or a store that can't say): `KEY_UNAVAILABLE` or
 *   `STORAGE`, and no key is made. History without its key goes to "reset history", which deletes the records first,
 *   then clears the key entry, then lets this make a new key; a crash in between leaves records without a key, which is
 *   the same state again, never a key without its records being opened by mistake.
 *
 * Only for a registered identity of the configured [origin], and under the storage's own [lock], on the load path too,
 * so it cannot race a registration, a wipe or a second call. The caller zeroes the returned array after handing it to
 * `HistoryCipherFactory.create`.
 */
internal class OpenHistoryKey(
    private val keys: HistoryKeyStore,
    private val identities: IdentityStore,
    private val origin: String,
    private val records: RecordStore,
    private val random: SecureRandomSource,
    private val lock: DeviceLock,
) {
    suspend operator fun invoke(): ByteArray =
        lock.holding {
            val identity = identities.load()
            check(identity?.deviceId != null && identity.origin == origin) { "a history key needs a registered identity of this origin" }
            keys.load() ?: create()
        }

    private suspend fun create(): ByteArray {
        requireEmptyRecords()
        return makeAndStore()
    }

    private suspend fun requireEmptyRecords() {
        val empty =
            try {
                records.isEmpty()
            } catch (_: RecordStoreException) {
                throw LocalStateException(STORAGE)
            }
        if (!empty) throw LocalStateException(KEY_UNAVAILABLE)
    }

    @Suppress("TooGenericExceptionCaught") // Any failure zeroes the key this call made, then is rethrown unchanged.
    private suspend fun makeAndStore(): ByteArray {
        val key = random.nextBytes(KEY_SIZE)
        try {
            check(key.size == KEY_SIZE) { "a history key is 32 bytes" }
            keys.save(key)
        } catch (e: Throwable) {
            key.fill(0)
            throw e
        }
        return key
    }

    private companion object {
        const val KEY_SIZE = 32
    }
}
