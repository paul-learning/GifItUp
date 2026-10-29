package app.gifitup.mls.storage

import app.gifitup.shared.data.storage.HistoryKeyStore
import app.gifitup.shared.data.storage.LocalStateException
import app.gifitup.shared.data.storage.LocalStateException.Problem.DAMAGED
import app.gifitup.shared.data.storage.LocalStateException.Problem.KEY_UNAVAILABLE
import app.gifitup.shared.data.storage.LocalStateException.Problem.STORAGE
import app.gifitup.shared.data.storage.LocalStore

/**
 * The history key (ADR-0017), stored only wrapped by the same [WrappingAead] as the snapshot key (ADR-0011): the same
 * layout `version(1) || iv(12) || ciphertext || tag(16)`, in its own entry, with AAD
 * `"gifitup history key v1" || 0x00 || version || origin`. The label is the only thing that tells the two wrapped keys
 * apart (same size, same wrapping key, same origin), so one never opens as the other. [origin] is the configured,
 * canonical one, never a value the server sent.
 *
 * Unlike [EnvelopeSnapshotKeyStore], this never makes a wrapping key: a missing one is `KEY_UNAVAILABLE`, so the history
 * key can never be the reason a fresh wrapping key orphans the snapshot key. It never replaces a stored key either.
 */
class EnvelopeHistoryKeyStore(
    private val aead: WrappingAead,
    private val store: LocalStore,
    private val origin: String,
) : HistoryKeyStore {
    override suspend fun load(): ByteArray? {
        val wrapped = store.get(KEY) ?: return null
        if (wrapped.size != EnvelopeSnapshotKeyStore.WRAPPED_SIZE || wrapped[0] != VERSION) throw LocalStateException(DAMAGED)
        val key = aead.open(wrapped.copyOfRange(1, wrapped.size), aad())
        if (key.size != EnvelopeSnapshotKeyStore.KEY_SIZE) {
            key.fill(0)
            throw LocalStateException(DAMAGED)
        }
        return key
    }

    override suspend fun save(key: ByteArray) {
        require(key.size == EnvelopeSnapshotKeyStore.KEY_SIZE) { "a history key is 32 bytes" }
        check(store.get(KEY) == null) { "a stored history key is never replaced" }
        if (!aead.exists()) throw LocalStateException(KEY_UNAVAILABLE)
        val sealed = aead.seal(key, aad())
        verify(sealed, key)
        store.putAll(mapOf(KEY to byteArrayOf(VERSION) + sealed))
    }

    /** The write path checks what [load] checks: the size, and that the sealed key opens to the same key. */
    private suspend fun verify(
        sealed: ByteArray,
        key: ByteArray,
    ) {
        if (sealed.size != EnvelopeSnapshotKeyStore.WRAPPED_SIZE - 1) throw LocalStateException(STORAGE)
        val opened = aead.open(sealed, aad())
        try {
            var difference = opened.size xor key.size
            for (i in 0 until minOf(opened.size, key.size)) difference = difference or (opened[i].toInt() xor key[i].toInt())
            if (difference != 0) throw LocalStateException(STORAGE)
        } finally {
            opened.fill(0)
        }
    }

    override suspend fun clear() = store.putAll(mapOf(KEY to null))

    private fun aad(): ByteArray = LABEL.encodeToByteArray() + byteArrayOf(0, VERSION) + origin.encodeToByteArray()

    internal companion object {
        const val KEY = "wrapped-history-key"
        private const val LABEL = "gifitup history key v1"
        private const val VERSION: Byte = 1
    }
}
