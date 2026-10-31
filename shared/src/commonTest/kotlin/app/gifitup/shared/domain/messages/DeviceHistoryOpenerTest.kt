package app.gifitup.shared.domain.messages

import app.gifitup.shared.data.content.GifReference
import app.gifitup.shared.data.crypto.FakeHistoryCipher
import app.gifitup.shared.data.crypto.HistoryCipher
import app.gifitup.shared.data.crypto.HistoryCipherFactory
import app.gifitup.shared.data.storage.InMemoryLocalStore
import app.gifitup.shared.data.storage.InMemoryRecordStore
import app.gifitup.shared.data.storage.StoredIdentity
import app.gifitup.shared.domain.auth.CountingRandom
import app.gifitup.shared.domain.auth.DeviceData
import app.gifitup.shared.domain.auth.DeviceStorage
import app.gifitup.shared.domain.auth.InMemoryHistoryKeyStore
import app.gifitup.shared.domain.auth.InMemoryKeyStore
import app.gifitup.shared.domain.contacts.KeyBytes
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The app's way to the history, end to end over fakes: the key, the cipher, the store, and the device lock held by a chat call. */
class DeviceHistoryOpenerTest {
    private val origin = "https://chat.example.test"
    private val records = InMemoryRecordStore()
    private val historyKeys = InMemoryHistoryKeyStore()
    private val data =
        object : DeviceData {
            override suspend fun destroyWrappingKey() = Unit

            override suspend fun deleteAll() = Unit
        }
    private val storage = DeviceStorage(InMemoryLocalStore(), data, records, { _, _ -> InMemoryKeyStore() }, { historyKeys })
    private val createdWith = mutableListOf<ByteArray>()
    private val ciphers =
        HistoryCipherFactory { key, _ ->
            createdWith += key.copyOf()
            FakeHistoryCipher() as HistoryCipher
        }
    private val gif =
        assertNotNull(
            GifReference.of("klipy", "cat", "https://static.klipy.com/ii/0123456789abcdef0123456789abcdef/ab/cd/cat-1.webp", 320, 240),
        )
    private val group = KeyBytes(ByteArray(16) { 1 })
    private val message = ChatMessage(group, KeyBytes(ByteArray(32) { 2 }), "bob", null, MessageBody.Gif(gif, null), null, 100L)

    private fun history() = LazyHistory(DeviceHistoryOpener(storage, origin, CountingRandom(), { ciphers }, HistoryLimits()), { 0L })

    private suspend fun register() = storage.identities.save(StoredIdentity(origin, "ann", byteArrayOf(1), "device-1"))

    /** The first history call comes from a sink inside a chat call, which holds the (not reentrant) device lock. */
    @Test
    fun theFirstOpenInsideAChatCallDoesNotWaitForTheDeviceLock() =
        runTest {
            register()
            val history = history()
            val inserted = storage.lock.holding { history.insert(message) }
            assertTrue(inserted)
            assertEquals(1, createdWith.size)
            assertEquals(32, createdWith.single().size)
            assertEquals(1, history.messages(group).size)
        }

    @Test
    fun theHistoryKeyIsMadeOnceAndTheSameOneOpensTheNextHistory() =
        runTest {
            register()
            history().insert(message)
            history().messages(group)
            assertEquals(1, historyKeys.saves)
            assertEquals(2, createdWith.size)
            assertTrue(createdWith[0].contentEquals(createdWith[1]))
        }

    @Test
    fun anUnregisteredDeviceGetsNoHistory() =
        runTest {
            val failure = kotlin.runCatching { history().insert(message) }.exceptionOrNull()
            assertEquals(HistoryStoreException.Kind.CIPHER, (failure as HistoryStoreException).kind)
            assertEquals(0, historyKeys.saves)
        }
}
