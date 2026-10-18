package app.gifitup.mls.storage

import app.gifitup.shared.data.storage.IdentityStore
import app.gifitup.shared.data.storage.LocalStateException
import app.gifitup.shared.data.storage.LocalStateException.Problem.DAMAGED
import app.gifitup.shared.data.storage.LocalStateException.Problem.KEY_UNAVAILABLE
import app.gifitup.shared.data.storage.LocalStateException.Problem.STORAGE
import app.gifitup.shared.data.storage.LocalStore
import app.gifitup.shared.data.storage.StoredIdentity
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

/** The wrapped-key format and its checks, over real AES-256-GCM from `javax.crypto` (ADR-0011). */
class EnvelopeSnapshotKeyStoreTest {
    /** A JCA AES-GCM key standing in for the platform key; it fails the way [WrappingAead] says implementations do. */
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
            val cipher = cipher(Cipher.ENCRYPT_MODE, iv, aad)
            return iv + cipher.doFinal(plaintext)
        }

        override suspend fun open(
            sealed: ByteArray,
            aad: ByteArray,
        ): ByteArray {
            val iv = sealed.copyOfRange(0, EnvelopeSnapshotKeyStore.IV_SIZE)
            val cipher = cipher(Cipher.DECRYPT_MODE, iv, aad)
            return try {
                cipher.doFinal(sealed, iv.size, sealed.size - iv.size)
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
                init(mode, key, GCMParameterSpec(TAG_BITS, iv))
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

    private class OneIdentity(
        var identity: StoredIdentity?,
    ) : IdentityStore {
        override suspend fun load() = identity

        override suspend fun save(identity: StoredIdentity) {
            this.identity = identity
        }

        override suspend fun clear() {
            identity = null
        }
    }

    private val aead = JcaTestAead()
    private val store = MapStore()
    private val identities = OneIdentity(null)
    private val keys = EnvelopeSnapshotKeyStore(aead, store, identities, ORIGIN)
    private val key = ByteArray(32) { it.toByte() }

    private fun wrapped() = store.entries.getValue(EnvelopeSnapshotKeyStore.KEY)

    @Test
    fun roundTripsAndStoresOnlyTheWrappedForm() =
        runTest {
            assertNull(keys.load())
            keys.save(key)
            assertEquals(1, aead.creates)
            val stored = wrapped()
            assertEquals(EnvelopeSnapshotKeyStore.WRAPPED_SIZE, stored.size)
            assertEquals(1, stored[0].toInt(), "version byte")
            assertEquals(-1, stored.asList().windowed(key.size).indexOf(key.asList()), "the raw key is never stored")
            assertContentEquals(key, keys.load())
            keys.clear()
            assertNull(keys.load())
            keys.save(key)
            assertEquals(1, aead.creates, "an existing wrapping key is reused")
        }

    @Test
    fun loadNeverCreatesAWrappingKey() =
        runTest {
            keys.save(key)
            aead.destroy()
            assertEquals(KEY_UNAVAILABLE, assertFailsWith<LocalStateException> { keys.load() }.problem)
            assertEquals(1, aead.creates)
        }

    @Test
    fun aWrappedKeyDoesntOpenUnderAnotherOrigin() =
        runTest {
            keys.save(key)
            val other = EnvelopeSnapshotKeyStore(aead, store, identities, "https://other.invalid")
            assertEquals(KEY_UNAVAILABLE, assertFailsWith<LocalStateException> { other.load() }.problem)
        }

    @Test
    fun theVersionByteIsAuthenticated() =
        runTest {
            keys.save(key)
            val stored = wrapped()
            stored[0] = 2
            store.entries[EnvelopeSnapshotKeyStore.KEY] = stored
            assertEquals(DAMAGED, assertFailsWith<LocalStateException> { keys.load() }.problem)
        }

    @Test
    fun aFlippedCiphertextBitDoesntOpen() =
        runTest {
            keys.save(key)
            val stored = wrapped()
            stored[EnvelopeSnapshotKeyStore.IV_SIZE + 5] = (stored[EnvelopeSnapshotKeyStore.IV_SIZE + 5].toInt() xor 1).toByte()
            store.entries[EnvelopeSnapshotKeyStore.KEY] = stored
            assertEquals(KEY_UNAVAILABLE, assertFailsWith<LocalStateException> { keys.load() }.problem)
        }

    @Test
    fun aWrongLengthIsDamaged() =
        runTest {
            keys.save(key)
            val stored = wrapped()
            store.entries[EnvelopeSnapshotKeyStore.KEY] = stored.copyOf(EnvelopeSnapshotKeyStore.WRAPPED_SIZE - 1)
            assertEquals(DAMAGED, assertFailsWith<LocalStateException> { keys.load() }.problem)
            store.entries[EnvelopeSnapshotKeyStore.KEY] = stored + 0
            assertEquals(DAMAGED, assertFailsWith<LocalStateException> { keys.load() }.problem)
        }

    /** A 31-byte plaintext that authenticates (sealed with the right key and AAD) is still refused. */
    @Test
    fun anUnwrappedKeyOfTheWrongSizeIsDamaged() =
        runTest {
            keys.save(key)
            val aad = "gifitup snapshot key v1".encodeToByteArray() + byteArrayOf(0, 1) + ORIGIN.encodeToByteArray()
            // 32 bytes of ciphertext need a 32-byte plaintext, so the size check only fires through a wrong-size AEAD.
            val short =
                object : WrappingAead by aead {
                    override suspend fun open(
                        sealed: ByteArray,
                        aad: ByteArray,
                    ) = aead.open(sealed, aad).copyOf(31)
                }
            assertContentEquals(key, aead.open(wrapped().copyOfRange(1, EnvelopeSnapshotKeyStore.WRAPPED_SIZE), aad))
            val shortKeys = EnvelopeSnapshotKeyStore(short, store, identities, ORIGIN)
            assertEquals(DAMAGED, assertFailsWith<LocalStateException> { shortKeys.load() }.problem)
        }

    @Test
    fun aPendingRegistrationMayReplaceItsKeyUnderTheSameWrappingKey() =
        runTest {
            keys.save(key)
            identities.identity = StoredIdentity(ORIGIN, "ann", byteArrayOf(1), deviceId = null)
            val next = ByteArray(32) { 7 }
            keys.save(next)
            assertEquals(1, aead.creates, "the wrapping key is never replaced while a wrapped key exists")
            assertContentEquals(next, keys.load())
        }

    @Test
    fun aRegisteredIdentitysKeyIsNeverReplaced() =
        runTest {
            keys.save(key)
            identities.identity = StoredIdentity(ORIGIN, "ann", byteArrayOf(1), deviceId = "d1")
            assertFailsWith<IllegalStateException> { keys.save(ByteArray(32) { 7 }) }
            assertContentEquals(key, keys.load())
        }

    /** A lost wrapping key with a wrapped key still stored is reported, never papered over with a new key (#16 SEC-5). */
    @Test
    fun saveDoesntCreateAWrappingKeyWhileAWrappedKeyExists() =
        runTest {
            keys.save(key)
            aead.destroy()
            assertEquals(KEY_UNAVAILABLE, assertFailsWith<LocalStateException> { keys.save(ByteArray(32) { 7 }) }.problem)
            assertEquals(1, aead.creates)
        }

    /** Also when no wrapped key is stored: a registered identity's snapshot would be orphaned (#111 CR-2). */
    @Test
    fun aRegisteredIdentityGetsNoKeyEvenWithoutAWrappedOne() =
        runTest {
            identities.identity = StoredIdentity(ORIGIN, "ann", byteArrayOf(1), deviceId = "d1")
            assertFailsWith<IllegalStateException> { keys.save(key) }
            assertNull(store.entries[EnvelopeSnapshotKeyStore.KEY])
        }

    /** A seal of the wrong size, or one that doesn't open to the same key, is never stored (#111 SEC-1). */
    @Test
    fun aSealThatWouldntLoadIsNeverStored() =
        runTest {
            val short =
                object : WrappingAead by aead {
                    override suspend fun seal(
                        plaintext: ByteArray,
                        aad: ByteArray,
                    ) = aead.seal(plaintext, aad).copyOf(EnvelopeSnapshotKeyStore.WRAPPED_SIZE - 2)
                }
            val other = ByteArray(32) { 9 }
            val wrongKey =
                object : WrappingAead by aead {
                    override suspend fun seal(
                        plaintext: ByteArray,
                        aad: ByteArray,
                    ) = aead.seal(other, aad)
                }
            for (broken in listOf(short, wrongKey)) {
                val brokenKeys = EnvelopeSnapshotKeyStore(broken, store, identities, ORIGIN)
                assertEquals(STORAGE, assertFailsWith<LocalStateException> { brokenKeys.save(key) }.problem)
                assertNull(store.entries[EnvelopeSnapshotKeyStore.KEY])
                assertNull(aead.key, "a wrapping key made by the failed save is dropped (#111 SEC-5)")
            }
            // One that already existed stays: a wrapped key may still need it.
            aead.create()
            val existing = aead.key
            assertFailsWith<LocalStateException> { EnvelopeSnapshotKeyStore(short, store, identities, ORIGIN).save(key) }
            assertEquals(existing, aead.key)
        }

    @Test
    fun saveRefusesAKeyThatIsNot32Bytes() =
        runTest {
            assertFailsWith<IllegalArgumentException> { keys.save(ByteArray(31)) }
            assertNull(store.entries[EnvelopeSnapshotKeyStore.KEY])
        }

    private companion object {
        const val ORIGIN = "https://e2e.invalid"
        const val TAG_BITS = 128
    }
}
