package app.gifitup.mls.storage

import app.gifitup.mls.toUint8Array
import app.gifitup.shared.data.storage.LocalStateException
import app.gifitup.shared.data.storage.LocalStateException.Problem.DAMAGED
import app.gifitup.shared.data.storage.LocalStateException.Problem.KEY_UNAVAILABLE
import app.gifitup.shared.data.storage.LocalStateException.Problem.STORAGE
import app.gifitup.shared.data.storage.StoredIdentity
import app.gifitup.shared.domain.auth.DeviceStorage
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import kotlin.js.Promise
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Suppress("UnusedParameter") // Read by the js() body, which detekt can't see.
private fun exportRaw(key: JsAny): Promise<JsAny?> = js("Promise.resolve().then(() => crypto.subtle.exportKey('raw', key))")

private fun extractableKey(): Promise<JsAny?> =
    js("crypto.subtle.generateKey({ name: 'AES-GCM', length: 256 }, true, ['encrypt', 'decrypt'])")

private fun notCloneable(): JsAny = js("(() => () => 0)()")

/** One real lock request on [name] per attempt (the app retries; most tests want a plain yes or no). */
private suspend fun claimForTest(
    name: String,
    attempts: Int = 1,
) = claimWebDeviceStorage(name, attempts) { requestPageLock(name).await<JsBoolean>().toBoolean() }

private fun jsText(): JsAny = js("'not bytes'")

/** The real IndexedDB, WebCrypto and Web Locks in headless Chrome (ADR-0011). Each test uses its own lock and database. */
class WebStorageTest {
    private val name = "gifitup-test-${Random.nextLong().toULong()}"
    private val key = ByteArray(EnvelopeSnapshotKeyStore.KEY_SIZE) { (it + 1).toByte() }

    private suspend fun withStorage(block: suspend (DeviceStorage) -> Unit) {
        val storage = assertNotNull(claimForTest(name))
        try {
            block(storage)
        } finally {
            storage.data.deleteAll()
        }
    }

    @Test
    fun wrapsAndUnwrapsTheSnapshotKeyAndKeepsTheIdentity() =
        runTest {
            withStorage { storage ->
                val keys = storage.snapshotKeys(ORIGIN)
                assertNull(keys.load())
                keys.save(key)
                assertContentEquals(key, keys.load())
                val wrapped = assertNotNull(storage.store.get(EnvelopeSnapshotKeyStore.KEY))
                assertEquals(EnvelopeSnapshotKeyStore.WRAPPED_SIZE, wrapped.size)
                val identity = StoredIdentity(ORIGIN, "ann", snapshot = key, deviceId = "d1")
                storage.identities.save(identity)
                assertEquals("d1", storage.identities.load()?.deviceId)
            }
        }

    @Test
    fun theWrappingKeyIsNotExtractable() =
        runTest {
            withStorage { storage ->
                storage.snapshotKeys(ORIGIN).save(key)
                val stored = assertNotNull(WebDatabase(name).get(WebDatabase.KEYS, WebCryptoWrappingAead.KEY))
                assertTrue(isWrappingKey(stored))
                assertFailsWith<LocalStateException> { settled(exportRaw(stored)) }
            }
        }

    /** An extractable key planted in the store is never used to wrap or unwrap. */
    @Test
    fun anExtractableKeyIsNotTheWrappingKey() =
        runTest {
            withStorage { storage ->
                val db = WebDatabase(name)
                db.putAll(WebDatabase.KEYS, mapOf(WebCryptoWrappingAead.KEY to settled(extractableKey())))
                val aead = WebCryptoWrappingAead(db)
                assertFalse(aead.exists())
                assertEquals(KEY_UNAVAILABLE, assertFailsWith<LocalStateException> { aead.seal(key, byteArrayOf()) }.problem)
                db.delete()
            }
        }

    @Test
    fun anotherWrappingKeyCantUnwrap() =
        runTest {
            withStorage { storage ->
                val keys = storage.snapshotKeys(ORIGIN)
                keys.save(key)
                val db = WebDatabase(name)
                db.putAll(WebDatabase.KEYS, mapOf(WebCryptoWrappingAead.KEY to settled(generateWrappingKey())))
                assertEquals(KEY_UNAVAILABLE, assertFailsWith<LocalStateException> { keys.load() }.problem)
                db.delete()
            }
        }

    @Test
    fun aMissingWrappingKeyIsUnavailableAndNeverRecreatedByLoad() =
        runTest {
            withStorage { storage ->
                val keys = storage.snapshotKeys(ORIGIN)
                keys.save(key)
                storage.data.destroyWrappingKey()
                assertEquals(KEY_UNAVAILABLE, assertFailsWith<LocalStateException> { keys.load() }.problem)
                assertFalse(WebCryptoWrappingAead(WebDatabase(name)).exists())
            }
        }

    /** If queueing any entry fails, none of the entries before it is committed. */
    @Test
    fun aFailedWriteLeavesEveryEntryAsItWas() =
        runTest {
            withStorage { storage ->
                storage.store.putAll(mapOf("a" to byteArrayOf(1)))
                val db = WebDatabase(name)
                val e =
                    assertFailsWith<LocalStateException> {
                        db.putAll(WebDatabase.DATA, mapOf("a" to byteArrayOf(2).toUint8Array(), "b" to notCloneable()))
                    }
                assertEquals(STORAGE, e.problem)
                assertContentEquals(byteArrayOf(1), storage.store.get("a"))
                assertNull(storage.store.get("b"))
                db.delete()
            }
        }

    @Test
    fun aValueThatIsNoByteArrayIsDamaged() =
        runTest {
            withStorage { storage ->
                val db = WebDatabase(name)
                db.putAll(WebDatabase.DATA, mapOf("a" to jsText()))
                assertEquals(DAMAGED, assertFailsWith<LocalStateException> { storage.store.get("a") }.problem)
                db.delete()
            }
        }

    @Test
    fun theWipeDeletesTheKeyAndTheDatabase() =
        runTest {
            val storage = assertNotNull(claimForTest(name))
            storage.snapshotKeys(ORIGIN).save(key)
            storage.store.putAll(mapOf("a" to byteArrayOf(1)))
            storage.data.destroyWrappingKey()
            storage.data.deleteAll()
            val reopened = WebDatabase(name)
            assertNull(reopened.get(WebDatabase.DATA, EnvelopeSnapshotKeyStore.KEY))
            assertNull(reopened.get(WebDatabase.DATA, "a"))
            assertNull(reopened.get(WebDatabase.KEYS, WebCryptoWrappingAead.KEY))
            reopened.delete()
        }

    private companion object {
        const val ORIGIN = "https://e2e.invalid"
    }
}

/** The device claim (Web Locks), a second connection during the wipe, and `persist()`. */
class WebDeviceTest {
    private companion object {
        const val ATTEMPTS = 3
    }

    private val name = "gifitup-test-${Random.nextLong().toULong()}"

    @Test
    fun aSecondClaimOfTheSameDeviceIsRefused() =
        runTest {
            val storage = assertNotNull(claimForTest(name))
            try {
                assertNull(claimForTest(name), "the lock is held for the page's lifetime")
            } finally {
                storage.data.deleteAll()
            }
        }

    /** Another open connection closes itself on `versionchange`, so it never blocks the wipe's delete. */
    @Test
    fun anotherOpenConnectionDoesntBlockTheWipe() =
        runTest {
            val storage = assertNotNull(claimForTest(name))
            storage.store.putAll(mapOf("a" to byteArrayOf(1)))
            val other = WebDatabase(name)
            assertNotNull(other.get(WebDatabase.DATA, "a"))
            storage.data.deleteAll()
            // The closed connection is opened again, onto the empty database: no stale "storage failed" until a reload.
            assertNull(other.get(WebDatabase.DATA, "a"))
            other.delete()
        }

    /** A page that reloads quickly may ask before the browser released the old page's lock (#112 SEC-2). */
    @Test
    fun theClaimAsksAgainBeforeGivingUp() =
        runTest {
            var asked = 0
            val storage = claimWebDeviceStorage(name, attempts = ATTEMPTS) { ++asked == ATTEMPTS }
            assertNotNull(storage)
            assertEquals(ATTEMPTS, asked)
            assertEquals((ATTEMPTS - 1) * RETRY_DELAY_MS, testScheduler.currentTime, "it waits between the asks, not after the last")
            storage.data.deleteAll()
            var refused = 0
            assertNull(claimWebDeviceStorage(name, attempts = ATTEMPTS) { ++refused < 0 })
            assertEquals(ATTEMPTS, refused)
            assertEquals(2 * (ATTEMPTS - 1) * RETRY_DELAY_MS, testScheduler.currentTime, "a refusal doesn't wait after the last ask")
        }

    @Test
    fun thePersistAnswerIsRecorded() =
        runTest {
            val storage = assertNotNull(claimForTest(name))
            try {
                val granted = requestPersistentStorage(storage.store)
                assertContentEquals(byteArrayOf(if (granted) 1 else 0), storage.store.get(PERSISTED_KEY))
            } finally {
                storage.data.deleteAll()
            }
        }
}
