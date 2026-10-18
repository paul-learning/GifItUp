package app.gifitup.mls.storage

import app.gifitup.shared.data.storage.IdentityStore
import app.gifitup.shared.data.storage.LocalStateException
import app.gifitup.shared.data.storage.LocalStateException.Problem.DAMAGED
import app.gifitup.shared.data.storage.LocalStateException.Problem.KEY_UNAVAILABLE
import app.gifitup.shared.data.storage.LocalStateException.Problem.STORAGE
import app.gifitup.shared.data.storage.LocalStore
import app.gifitup.shared.data.storage.SnapshotKeyStore

/**
 * A platform AES-256-GCM key that never leaves the platform (Android Keystore, WebCrypto; ADR-0011). Implementations
 * map a missing or invalidated key and a failed authentication to [LocalStateException] with `KEY_UNAVAILABLE` (the two
 * can't be told apart, and neither may count as a failed restore; ADR-0011), and any other platform failure to
 * `STORAGE`; never to a raw platform exception.
 */
interface WrappingAead {
    suspend fun exists(): Boolean

    /** Creates the key. Only [EnvelopeSnapshotKeyStore.save] calls it, and only when no wrapped key exists (#16 SEC-5). */
    suspend fun create()

    /** `iv(12) || ciphertext || tag(16)`, with a fresh random IV from the platform. */
    suspend fun seal(
        plaintext: ByteArray,
        aad: ByteArray,
    ): ByteArray

    /** The plaintext of [seal]'s output; [LocalStateException] if the key is missing or the input doesn't authenticate. */
    suspend fun open(
        sealed: ByteArray,
        aad: ByteArray,
    ): ByteArray

    /** Deletes the key; a missing key is no error, so a retried wipe completes (#108 SEC-18). */
    suspend fun destroy()
}

/**
 * The snapshot key, stored only wrapped by a [WrappingAead] (envelope encryption, ADR-0011). The wrapped form is
 * `version(1) || iv(12) || ciphertext || tag(16)`, with AAD `"gifitup snapshot key v1" || 0x00 || version || origin`, so
 * the version is authenticated and a wrapped key can't move to another origin's slot. [origin] is the configured,
 * canonical one (never a value the server sent).
 *
 * The wrapping key is created only by [save], and only when no wrapped key is stored; [load] never creates one, so a
 * missing key is `KEY_UNAVAILABLE`, never a fresh key that would orphan the stored one (#16 SEC-5).
 */
class EnvelopeSnapshotKeyStore(
    private val aead: WrappingAead,
    private val store: LocalStore,
    private val identities: IdentityStore,
    private val origin: String,
) : SnapshotKeyStore {
    override suspend fun load(): ByteArray? {
        val wrapped = store.get(KEY) ?: return null
        if (wrapped.size != WRAPPED_SIZE || wrapped[0] != VERSION) throw LocalStateException(DAMAGED)
        val key = aead.open(wrapped.copyOfRange(1, wrapped.size), aad())
        if (key.size != KEY_SIZE) {
            key.fill(0)
            throw LocalStateException(DAMAGED)
        }
        return key
    }

    override suspend fun save(key: ByteArray) {
        require(key.size == KEY_SIZE) { "a snapshot key is 32 bytes" }
        // Defence in depth: only a pending registration saves a key, also when no wrapped key is stored (#16 SEC-5,
        // #111 CR-2).
        check(identities.load()?.deviceId == null) { "a registered identity's key is never replaced" }
        var created = false
        if (store.get(KEY) != null) {
            if (!aead.exists()) throw LocalStateException(KEY_UNAVAILABLE)
        } else if (!aead.exists()) {
            aead.create()
            created = true
        }
        val sealed = aead.seal(key, aad())
        try {
            verify(sealed, key)
        } catch (e: LocalStateException) {
            // A wrapping key this call made and that doesn't round-trip is dropped, so the next registration makes a
            // fresh one instead of failing on it until a wipe (#111 SEC-5). Nothing is wrapped with it yet.
            if (created) runCatching { aead.destroy() }
            throw e
        }
        store.putAll(mapOf(KEY to byteArrayOf(VERSION) + sealed))
    }

    /**
     * The write path checks what [load] checks: the size, and that the sealed key opens to the same key, so a broken
     * platform key fails here, before the identity is registered, not on the next start (#111 SEC-1).
     */
    private suspend fun verify(
        sealed: ByteArray,
        key: ByteArray,
    ) {
        if (sealed.size != WRAPPED_SIZE - 1) throw LocalStateException(STORAGE)
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
        const val KEY = "wrapped-snapshot-key"
        private const val LABEL = "gifitup snapshot key v1"
        private const val VERSION: Byte = 1
        const val KEY_SIZE = 32
        const val IV_SIZE = 12
        private const val TAG_SIZE = 16
        const val WRAPPED_SIZE = 1 + IV_SIZE + KEY_SIZE + TAG_SIZE
    }
}
