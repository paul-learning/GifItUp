package app.gifitup.shared.domain.messages

import app.gifitup.shared.data.content.GifReference
import app.gifitup.shared.data.crypto.FakeHistoryCipher
import app.gifitup.shared.data.crypto.HistoryCipher
import app.gifitup.shared.data.crypto.HistoryCipherFactory
import app.gifitup.shared.data.storage.InMemoryLocalStore
import app.gifitup.shared.data.storage.InMemoryRecordStore
import app.gifitup.shared.data.storage.LocalStateException
import app.gifitup.shared.data.storage.StoredIdentity
import app.gifitup.shared.domain.auth.CountingRandom
import app.gifitup.shared.domain.auth.DeviceData
import app.gifitup.shared.domain.auth.DeviceStorage
import app.gifitup.shared.domain.auth.InMemoryHistoryKeyStore
import app.gifitup.shared.domain.auth.InMemoryKeyStore
import app.gifitup.shared.domain.contacts.KeyBytes
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** "Reset history" (ADR-0017): the order of the deletion, the gates it passes, and what it leaves alone. */
class DeviceHistoryResetterTest {
    private val origin = "https://chat.example.test"
    private val records = InMemoryRecordStore()
    private val historyKeys = InMemoryHistoryKeyStore()
    private val data =
        object : DeviceData {
            override suspend fun destroyWrappingKey() = Unit

            override suspend fun deleteAll() = Unit
        }
    private val snapshotKeys = InMemoryKeyStore(ByteArray(32) { 3 })
    private val storage = DeviceStorage(InMemoryLocalStore(), data, records, { _, _ -> snapshotKeys }, { historyKeys })
    private val ciphers = mutableListOf<FakeHistoryCipher>()
    private val factory =
        HistoryCipherFactory { _, _ ->
            FakeHistoryCipher().also { ciphers += it } as HistoryCipher
        }
    private val gif =
        assertNotNull(
            GifReference.of("klipy", "cat", "https://static.klipy.com/ii/0123456789abcdef0123456789abcdef/ab/cd/cat-1.webp", 320, 240),
        )
    private val group = KeyBytes(ByteArray(16) { 1 })
    private val message = ChatMessage(group, KeyBytes(ByteArray(32) { 2 }), "bob", null, MessageBody.Gif(gif, null), null, 100L)

    private fun history(resetter: HistoryResetter? = DeviceHistoryResetter(storage, origin)) =
        LazyHistory(
            DeviceHistoryOpener(storage, origin, CountingRandom(), { factory }, HistoryLimits()),
            { 0L },
            resetter = resetter,
        )

    private suspend fun register() = storage.identities.save(StoredIdentity(origin, "ann", byteArrayOf(1), "device-1"))

    /** The history is in the state a reset is for: a key entry that does not open, records behind it. */
    private suspend fun brokenKeyWithRecords(history: LazyHistory) {
        historyKeys.key = ByteArray(32)
        historyKeys.problem = LocalStateException.Problem.KEY_UNAVAILABLE
        records.put("ab".repeat(32), "cd".repeat(32), byteArrayOf(1))
        val failure = runCatching { history.messages(group) }.exceptionOrNull() as HistoryStoreException
        assertEquals(HistoryStoreException.Kind.KEY, failure.kind)
        historyKeys.problem = null
    }

    @Test
    fun aResetDeletesTheRecordsAndTheKeyAndTheNextUseStartsOverWithANewKey() =
        runTest {
            register()
            val history = history()
            brokenKeyWithRecords(history)
            assertTrue(history.canReset())
            assertEquals(ResetResult.DONE, history.reset())
            assertNull(historyKeys.key, "the wrapped key entry is gone")
            assertTrue(records.isEmpty())
            assertEquals(emptyList(), history.messages(group))
            assertEquals(1, historyKeys.saves, "a new key was made over the empty store")
            assertFalse(history.canReset())
        }

    @Test
    fun aHealthyHistoryIsNeverResetEvenIfAScreenStillOffersIt() =
        runTest {
            register()
            val history = history()
            history.insert(message)
            assertFalse(history.canReset())
            assertEquals(ResetResult.UNAVAILABLE, history.reset())
            assertEquals(1, history.messages(group).size, "what was saved stays")
        }

    @Test
    fun aSecondResetAfterTheFirstDeletesNothingMore() =
        runTest {
            register()
            val history = history()
            brokenKeyWithRecords(history)
            assertEquals(ResetResult.DONE, history.reset())
            history.insert(message)
            assertEquals(ResetResult.UNAVAILABLE, history.reset(), "a second tap or a stale flag")
            assertEquals(1, history.messages(group).size, "the message saved since the first reset survives")
        }

    @Test
    fun whenTheSnapshotKeyFailsTooThereIsNoResetAndNothingIsDeleted() =
        runTest {
            register()
            val history = history()
            brokenKeyWithRecords(history)
            snapshotKeys.key = null
            assertFalse(history.canReset())
            assertEquals(ResetResult.UNAVAILABLE, history.reset())
            assertFalse(records.isEmpty())
            assertNotNull(historyKeys.key)
        }

    @Test
    fun anUnregisteredDeviceHasNothingToReset() =
        runTest {
            records.put("ab".repeat(32), "cd".repeat(32), byteArrayOf(1))
            assertEquals(ResetResult.UNAVAILABLE, history().reset())
            assertFalse(records.isEmpty(), "nothing was deleted")
        }

    @Test
    fun aHistoryWithoutAResetterOffersNone() =
        runTest {
            register()
            val history = history(resetter = null)
            assertEquals(ResetResult.UNAVAILABLE, history.reset())
            assertFalse(history.canReset())
        }

    @Test
    fun aWipedHistoryCannotBeReset() =
        runTest {
            register()
            val history = history()
            history.close()
            assertEquals(ResetResult.UNAVAILABLE, history.reset())
        }

    @Test
    fun aResetWaitsForAChatCallThatIsRunningAndNothingRunsMeanwhile() =
        runTest {
            register()
            val history = history()
            brokenKeyWithRecords(history)
            val reset =
                storage.lock.holding {
                    val r = async { history.reset() }
                    runCurrent()
                    assertFalse(r.isCompleted, "a send, a sync or a reconcile holds the device lock; the reset comes after it")
                    assertFalse(records.isEmpty())
                    r
                }
            assertEquals(ResetResult.DONE, reset.await())
            assertTrue(records.isEmpty())
        }

    @Test
    fun aDeletionThatFailsIsReportedKeepsTheOfferAndAnotherResetFinishesIt() =
        runTest {
            register()
            val history = history()
            brokenKeyWithRecords(history)
            historyKeys.clearFails = true
            assertEquals(ResetResult.FAILED, history.reset())
            assertTrue(records.isEmpty(), "the records went first")
            assertTrue(history.canReset(), "the retry the screen promises is still allowed")
            historyKeys.clearFails = false
            assertEquals(ResetResult.DONE, history.reset())
            assertNull(historyKeys.key)
        }
}
