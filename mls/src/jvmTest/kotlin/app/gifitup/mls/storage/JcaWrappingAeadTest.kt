package app.gifitup.mls.storage

import app.gifitup.shared.data.storage.LocalStateException
import app.gifitup.shared.data.storage.LocalStateException.Problem.KEY_UNAVAILABLE
import app.gifitup.shared.data.storage.LocalStateException.Problem.STORAGE
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `JcaWrappingAead` over a software AES key, and the Android storage as a whole (everything but the Keystore itself). */
class JcaWrappingAeadTest {
    private class SoftwareKeys : WrappingKeySupplier {
        var key: SecretKey? = null
        var failWith: Throwable? = null

        override fun get(): SecretKey? = failWith?.let { throw it } ?: key

        override fun create(): SecretKey =
            KeyGenerator
                .getInstance("AES")
                .apply { init(256) }
                .generateKey()
                .also { key = it }

        override fun delete() {
            key = null
        }
    }

    private val keys = SoftwareKeys()
    private val aead = JcaWrappingAead(keys)
    private val aad = byteArrayOf(1, 2, 3)
    private val plain = ByteArray(32) { it.toByte() }

    @Test
    fun sealsAndOpensWithAFreshIvEachTime() =
        runTest {
            assertFalse(aead.exists())
            aead.create()
            assertTrue(aead.exists())
            val first = aead.seal(plain, aad)
            val second = aead.seal(plain, aad)
            assertEquals(12 + 32 + 16, first.size)
            assertFalse(first.copyOf(12).contentEquals(second.copyOf(12)), "a new IV per seal")
            assertContentEquals(plain, aead.open(first, aad))
        }

    @Test
    fun createNeverReplacesAnExistingKey() =
        runTest {
            aead.create()
            val sealed = aead.seal(plain, aad)
            val first = keys.key
            assertEquals(STORAGE, assertFailsWith<LocalStateException> { aead.create() }.problem)
            assertEquals(first, keys.key)
            assertContentEquals(plain, aead.open(sealed, aad))
        }

    @Test
    fun aWrongAadOrChangedBytesAreKeyUnavailable() =
        runTest {
            aead.create()
            val sealed = aead.seal(plain, aad)
            assertEquals(KEY_UNAVAILABLE, assertFailsWith<LocalStateException> { aead.open(sealed, byteArrayOf(9)) }.problem)
            val flipped = sealed.copyOf().also { it[20] = (it[20].toInt() xor 1).toByte() }
            assertEquals(KEY_UNAVAILABLE, assertFailsWith<LocalStateException> { aead.open(flipped, aad) }.problem)
            assertEquals(KEY_UNAVAILABLE, assertFailsWith<LocalStateException> { aead.open(sealed.copyOf(20), aad) }.problem)
        }

    @Test
    fun aMissingKeyIsUnavailableAndNothingCreatesOne() =
        runTest {
            assertEquals(KEY_UNAVAILABLE, assertFailsWith<LocalStateException> { aead.seal(plain, aad) }.problem)
            assertEquals(KEY_UNAVAILABLE, assertFailsWith<LocalStateException> { aead.open(ByteArray(60), aad) }.problem)
            assertNull(keys.key)
        }

    @Test
    fun anotherKeyCantOpenAndDestroyIsIdempotent() =
        runTest {
            aead.create()
            val sealed = aead.seal(plain, aad)
            aead.destroy()
            aead.destroy()
            assertFalse(aead.exists())
            aead.create()
            assertEquals(KEY_UNAVAILABLE, assertFailsWith<LocalStateException> { aead.open(sealed, aad) }.problem)
        }

    /** The Keystore reports a lost or invalidated key as these; none may reach Kotlin as a raw exception. */
    @Test
    fun platformFailuresAreMappedToClosedErrors() =
        runTest {
            val cases =
                listOf(
                    java.security.UnrecoverableKeyException() to KEY_UNAVAILABLE,
                    java.security.InvalidKeyException() to KEY_UNAVAILABLE,
                    java.security.KeyStoreException() to STORAGE,
                    java.io.IOException() to STORAGE,
                    java.security.ProviderException() to STORAGE,
                )
            for ((failure, problem) in cases) {
                keys.failWith = failure
                assertEquals(problem, assertFailsWith<LocalStateException> { aead.exists() }.problem, failure.toString())
                assertEquals(problem, assertFailsWith<LocalStateException> { aead.open(ByteArray(60), aad) }.problem)
            }
        }

    /** The Android storage end to end: register a key, restart, then wipe (ADR-0011, #16 SEC-3, SEC-8). */
    @Test
    fun theWholeStorageSurvivesARestartAndTheWipeDestroysKeyAndFile() =
        runTest {
            val dir = Files.createTempDirectory("gifitup-android").toFile()
            try {
                val origin = "https://e2e.invalid"
                val storage = fileDeviceStorage(hostStore(dir.resolve("state")), keys)
                val snapshotKey = ByteArray(32) { 7 }
                storage.snapshotKeys(origin).save(snapshotKey)
                val restarted = fileDeviceStorage(hostStore(dir.resolve("state")), keys)
                assertContentEquals(snapshotKey, restarted.snapshotKeys(origin).load())
                assertEquals(
                    KEY_UNAVAILABLE,
                    assertFailsWith<LocalStateException> { restarted.snapshotKeys("https://other.invalid").load() }.problem,
                )
                restarted.data.destroyWrappingKey()
                restarted.data.deleteAll()
                assertNull(keys.key)
                assertEquals(
                    emptyList(),
                    dir
                        .resolve("state")
                        .list()
                        .orEmpty()
                        .toList(),
                )
                assertNull(restarted.snapshotKeys(origin).load(), "nothing is left to load")
                restarted.data.destroyWrappingKey()
                restarted.data.deleteAll()
            } finally {
                dir.deleteRecursively()
            }
        }
}
