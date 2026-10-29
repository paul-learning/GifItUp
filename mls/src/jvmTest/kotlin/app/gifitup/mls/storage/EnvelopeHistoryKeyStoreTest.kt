package app.gifitup.mls.storage

import app.gifitup.shared.data.storage.LocalStateException
import app.gifitup.shared.data.storage.LocalStateException.Problem.DAMAGED
import app.gifitup.shared.data.storage.LocalStateException.Problem.KEY_UNAVAILABLE
import app.gifitup.shared.data.storage.LocalStore
import kotlinx.coroutines.test.runTest
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** The wrapped history key: its format, its separation from the snapshot key, and that it never makes a wrapping key. */
class EnvelopeHistoryKeyStoreTest {
    private class JcaTestAead : WrappingAead {
        var key: SecretKey? = null
        var creates = 0

        override suspend fun exists() = key != null

        override suspend fun create() {
            creates++
            key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        }

        override suspend fun seal(
            plaintext: ByteArray,
            aad: ByteArray,
        ): ByteArray {
            val iv = ByteArray(EnvelopeSnapshotKeyStore.IV_SIZE).also(SecureRandom()::nextBytes)
            return iv + cipher(Cipher.ENCRYPT_MODE, iv, aad).doFinal(plaintext)
        }

        override suspend fun open(
            sealed: ByteArray,
            aad: ByteArray,
        ): ByteArray {
            val iv = sealed.copyOfRange(0, EnvelopeSnapshotKeyStore.IV_SIZE)
            return try {
                cipher(Cipher.DECRYPT_MODE, iv, aad).doFinal(sealed, iv.size, sealed.size - iv.size)
            } catch (_: AEADBadTagException) {
                throw LocalStateException(KEY_UNAVAILABLE)
            }
        }

        override suspend fun destroy() {
            key = null
        }

        private fun cipher(
            mode: Int,
            iv: ByteArray,
            aad: ByteArray,
        ): Cipher {
            val key = key ?: throw LocalStateException(KEY_UNAVAILABLE)
            return Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(mode, key, GCMParameterSpec(128, iv))
                updateAAD(aad)
            }
        }
    }

    private class MapStore : LocalStore {
        val entries = mutableMapOf<String, ByteArray>()

        override suspend fun get(key: String) = entries[key]?.copyOf()

        override suspend fun putAll(entries: Map<String, ByteArray?>) =
            entries.forEach { (key, value) -> if (value == null) this.entries.remove(key) else this.entries[key] = value.copyOf() }
    }

    private val aead = JcaTestAead()
    private val store = MapStore()
    private val keys = EnvelopeHistoryKeyStore(aead, store, ORIGIN)
    private val key = ByteArray(32) { (it + 7).toByte() }

    private suspend fun withWrappingKey() = aead.create()

    private fun wrapped() = store.entries.getValue(EnvelopeHistoryKeyStore.KEY)

    @Test
    fun roundTripsAndStoresOnlyTheWrappedForm() =
        runTest {
            withWrappingKey()
            assertNull(keys.load())
            keys.save(key)
            val stored = wrapped()
            assertEquals(EnvelopeSnapshotKeyStore.WRAPPED_SIZE, stored.size)
            assertEquals(-1, stored.asList().windowed(key.size).indexOf(key.asList()), "the raw key is never stored")
            assertContentEquals(key, keys.load())
            keys.clear()
            assertNull(keys.load())
        }

    @Test
    fun savingWithoutAWrappingKeyIsKeyUnavailableAndMakesNone() =
        runTest {
            assertEquals(KEY_UNAVAILABLE, assertFailsWith<LocalStateException> { keys.save(key) }.problem)
            assertEquals(0, aead.creates)
            assertNull(store.entries[EnvelopeHistoryKeyStore.KEY])
        }

    @Test
    fun loadNeverCreatesAWrappingKeyAndAStoredKeyIsNeverReplaced() =
        runTest {
            withWrappingKey()
            keys.save(key)
            assertFailsWith<IllegalStateException> { keys.save(ByteArray(32)) }
            aead.destroy()
            assertEquals(KEY_UNAVAILABLE, assertFailsWith<LocalStateException> { keys.load() }.problem)
            assertEquals(1, aead.creates)
        }

    @Test
    fun theSnapshotKeyAndTheHistoryKeyDoNotOpenAsEachOther() =
        runTest {
            withWrappingKey()
            keys.save(key)
            val snapshotAad = "gifitup snapshot key v1".encodeToByteArray() + byteArrayOf(0, 1) + ORIGIN.encodeToByteArray()
            // The history key's blob stored where the snapshot key's goes, and the reverse.
            val sealedAsSnapshot = aead.seal(key, snapshotAad)
            store.entries[EnvelopeHistoryKeyStore.KEY] = byteArrayOf(1) + sealedAsSnapshot
            assertEquals(KEY_UNAVAILABLE, assertFailsWith<LocalStateException> { keys.load() }.problem)
        }

    @Test
    fun aWrappedKeyDoesntOpenUnderAnotherOriginOrWhenChanged() =
        runTest {
            withWrappingKey()
            keys.save(key)
            val other = EnvelopeHistoryKeyStore(aead, store, "https://other.example")
            assertEquals(KEY_UNAVAILABLE, assertFailsWith<LocalStateException> { other.load() }.problem)
            val flipped = wrapped().also { it[20] = (it[20].toInt() xor 1).toByte() }
            store.entries[EnvelopeHistoryKeyStore.KEY] = flipped
            assertEquals(KEY_UNAVAILABLE, assertFailsWith<LocalStateException> { keys.load() }.problem)
        }

    @Test
    fun aTruncatedOrOtherVersionEntryIsDamaged() =
        runTest {
            withWrappingKey()
            keys.save(key)
            store.entries[EnvelopeHistoryKeyStore.KEY] = wrapped().copyOf(10)
            assertEquals(DAMAGED, assertFailsWith<LocalStateException> { keys.load() }.problem)
            store.entries[EnvelopeHistoryKeyStore.KEY] = wrapped().also { it[0] = 2 }
            assertEquals(DAMAGED, assertFailsWith<LocalStateException> { keys.load() }.problem)
        }

    private companion object {
        const val ORIGIN = "https://gifitup.example"
    }
}
