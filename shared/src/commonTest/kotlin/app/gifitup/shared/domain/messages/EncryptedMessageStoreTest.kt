package app.gifitup.shared.domain.messages

import app.gifitup.shared.data.content.GifReference
import app.gifitup.shared.data.crypto.FakeHistoryCipher
import app.gifitup.shared.data.storage.InMemoryRecordStore
import app.gifitup.shared.domain.contacts.KeyBytes
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The durable history over a record store and a cipher (ADR-0017), with the fake cipher: the store's own rules. */
class EncryptedMessageStoreTest {
    private val url = "https://static.klipy.com/ii/0123456789abcdef0123456789abcdef/ab/cd/funny_cat-1.webp"
    private val gif = assertNotNull(GifReference.of("klipy", "funny-cat", url, 320, 240))
    private val records = InMemoryRecordStore()
    private val cipher = FakeHistoryCipher()

    private fun id(n: Int) = KeyBytes(ByteArray(16) { n.toByte() })

    private fun ref(n: Int) = KeyBytes(ByteArray(32) { n.toByte() })

    private fun store(
        limits: HistoryLimits = HistoryLimits(),
        c: FakeHistoryCipher = cipher,
    ) = EncryptedMessageStore(c, records, limits)

    private fun received(
        group: Int,
        n: Int,
        caption: String? = null,
        at: Long = 100L + n,
    ) = ChatMessage(id(group), ref(n), "bob", null, MessageBody.Gif(gif, caption), null, at)

    private fun own(
        group: Int,
        n: Int,
    ) = ChatMessage(id(group), ref(n), null, null, MessageBody.Gif(gif, "mine"), DeliveryState.SENDING, 50L)

    @Test
    fun aMessageIsStoredOnceInArrivalOrderAndFirstOneWins() =
        runTest {
            val s = store()
            assertTrue(s.insert(received(1, 2)))
            assertTrue(s.insert(received(1, 1)))
            assertFalse(s.insert(ChatMessage(id(1), ref(2), "mallory", null, MessageBody.Unavailable, null, 1L)))
            val list = s.messages(id(1))
            assertEquals(listOf(ref(2), ref(1)), list.map { it.reference })
            assertEquals("bob", list.first().sender)
            assertEquals(emptyList(), s.messages(id(9)))
        }

    @Test
    fun theHistorySurvivesANewStoreOverTheSameRecords() =
        runTest {
            val first = store()
            first.insert(received(1, 1, caption = "hello 👋"))
            first.insert(own(1, 2))
            first.setState(id(1), ref(2), DeliveryState.SENT, seq = 7)
            first.insert(ChatMessage(id(1), ref(3), "cy", 3L, MessageBody.GroupMeta("Trip"), null, 5L))
            first.insert(ChatMessage(id(1), ref(4), "cy", null, MessageBody.UpdateNeeded, null, 6L))
            val again = store().messages(id(1))
            assertEquals(4, again.size)
            assertEquals("hello 👋", assertIs<MessageBody.Gif>(again[0].body).caption)
            assertEquals(gif, assertIs<MessageBody.Gif>(again[0].body).reference)
            assertEquals(DeliveryState.SENT, again[1].state)
            assertEquals(7L, again[1].seq)
            assertNull(again[1].sender)
            assertEquals("Trip", assertIs<MessageBody.GroupMeta>(again[2].body).name)
            assertIs<MessageBody.UpdateNeeded>(again[3].body)
            assertEquals(listOf(100L + 1, 50L, 5L, 6L), again.map { it.at })
        }

    @Test
    fun arrivalOrderContinuesAfterARestart() =
        runTest {
            store().insert(received(1, 1))
            val second = store()
            second.insert(received(1, 2))
            assertEquals(listOf(ref(1), ref(2)), store().messages(id(1)).map { it.reference })
        }

    @Test
    fun onlyAnOwnMessageChangesState() =
        runTest {
            val s = store()
            s.insert(received(1, 1))
            s.insert(own(1, 2))
            s.setState(id(1), ref(1), DeliveryState.FAILED)
            s.setState(id(1), ref(9), DeliveryState.FAILED)
            s.setState(id(7), ref(2), DeliveryState.FAILED)
            s.setState(id(1), ref(2), DeliveryState.FAILED)
            val list = store().messages(id(1))
            assertNull(list[0].state)
            assertEquals(DeliveryState.FAILED, list[1].state)
        }

    @Test
    fun retentionIsPerGroupAndDeletesTheOldestRecords() =
        runTest {
            val s = store(HistoryLimits(maxPerGroup = 3, maxGroups = 10))
            for (n in 1..5) s.insert(received(1, n))
            s.insert(received(2, 1))
            assertEquals(listOf(ref(3), ref(4), ref(5)), s.messages(id(1)).map { it.reference })
            assertEquals(1, s.messages(id(2)).size)
            val reopened = store(HistoryLimits(maxPerGroup = 3, maxGroups = 10))
            assertEquals(listOf(ref(3), ref(4), ref(5)), reopened.messages(id(1)).map { it.reference })
            assertEquals(4, records.snapshot().values.sumOf { it.size }, "three of group 1 and one of group 2 are on disk")
        }

    @Test
    fun aFloodInOneGroupNeverEvictsAnotherGroup() =
        runTest {
            val s = store(HistoryLimits(maxPerGroup = 2, maxGroups = 2))
            s.insert(received(1, 1))
            for (n in 1..20) s.insert(received(2, n))
            assertEquals(1, s.messages(id(1)).size)
            assertEquals(2, s.messages(id(2)).size)
        }

    @Test
    fun aNewGroupOverTheGroupLimitGetsNoRecordsButTheOthersKeepWorking() =
        runTest {
            val s = store(HistoryLimits(maxPerGroup = 5, maxGroups = 2))
            assertTrue(s.insert(received(1, 1)))
            assertTrue(s.insert(received(2, 1)))
            assertFalse(s.insert(received(3, 1)))
            assertTrue(s.insert(received(1, 2)))
            assertEquals(emptyList(), s.messages(id(3)))
            assertEquals(2, records.folders().size)
            // The limit holds after a restart too.
            assertFalse(store(HistoryLimits(maxPerGroup = 5, maxGroups = 2)).insert(received(3, 2)))
        }

    @Test
    fun aRecordThatDoesNotOpenIsCountedKeptAndNeverShown() =
        runTest {
            val first = store()
            first.insert(received(1, 1, caption = "one"))
            first.insert(received(1, 2, caption = "two"))
            first.insert(received(1, 3, caption = "three"))
            val folder = records.folders().single()
            val (name, bytes) =
                records
                    .snapshot()
                    .getValue(folder)
                    .entries
                    .elementAt(1)
            records.put(folder, name, bytes.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() })
            // A record moved under another name doesn't pass either.
            val other =
                records
                    .snapshot()
                    .getValue(folder)
                    .entries
                    .first()
            records.put(folder, "f".repeat(64), other.value)
            val reopened = store()
            assertEquals(2, reopened.messages(id(1)).size)
            assertEquals(2, reopened.unreadable(id(1)))
            assertEquals(4, records.list(folder).size, "nothing was deleted for failing to open")
        }

    @Test
    fun theWrongKeyReadsNothingAndDeletesNothing() =
        runTest {
            store().insert(received(1, 1))
            val wrong = store(c = FakeHistoryCipher(key = 8))
            assertEquals(emptyList(), wrong.messages(id(1)))
            assertEquals(0, wrong.unreadable(id(1)), "another key doesn't even find the group's folder")
            assertEquals(1, records.snapshot().values.sumOf { it.size })
        }

    @Test
    fun aStoreFailureIsReportedNothingIsShownAndTheRetryStoresItOnce() =
        runTest {
            val s = store()
            records.failNextWrite = true
            val failure = assertFailsWith<HistoryStoreException> { s.insert(received(1, 1)) }
            assertEquals(HistoryStoreException.Kind.STORAGE, failure.kind)
            assertEquals(emptyList(), s.messages(id(1)))
            assertTrue(s.insert(received(1, 1)))
            assertFalse(s.insert(received(1, 1)))
            assertEquals(1, store().messages(id(1)).size)
        }

    @Test
    fun aFailedStateWriteLeavesTheOldState() =
        runTest {
            val s = store()
            s.insert(own(1, 1))
            records.failNextWrite = true
            assertFailsWith<HistoryStoreException> { s.setState(id(1), ref(1), DeliveryState.SENT, 3L) }
            assertEquals(DeliveryState.SENDING, s.messages(id(1)).single().state)
            assertEquals(DeliveryState.SENDING, store().messages(id(1)).single().state)
        }

    @Test
    fun aRecordTooBigToSealIsKeptAsUnavailableAndDoesNotBlock() =
        runTest {
            val small = FakeHistoryCipher(maxPlaintext = 300)
            val s = store(c = small)
            assertTrue(s.insert(received(1, 1, caption = "x".repeat(200))))
            assertTrue(s.insert(received(1, 2)))
            val list = store(c = small).messages(id(1))
            assertIs<MessageBody.Unavailable>(list[0].body)
            assertIs<MessageBody.Gif>(list[1].body)
        }

    @Test
    fun aPoisonedCipherIsACipherFailureNotAStorageFailure() =
        runTest {
            val c = FakeHistoryCipher()
            val s = store(c = c)
            s.insert(received(1, 1))
            c.poisoned = true
            assertEquals(HistoryStoreException.Kind.CIPHER, assertFailsWith<HistoryStoreException> { s.insert(received(1, 2)) }.kind)
            assertEquals(HistoryStoreException.Kind.CIPHER, assertFailsWith<HistoryStoreException> { store(c = c).messages(id(1)) }.kind)
            c.poisoned = false
            assertEquals(1, store(c = c).messages(id(1)).size, "nothing was lost or deleted meanwhile")
        }

    @Test
    fun forgettingAGroupAndResettingDeleteTheRecords() =
        runTest {
            val s = store()
            s.insert(received(1, 1))
            s.insert(received(2, 1))
            s.forget(id(1))
            assertEquals(emptyList(), s.messages(id(1)))
            assertEquals(1, records.folders().size)
            assertEquals(1, store().messages(id(2)).size)
            s.deleteAll()
            assertEquals(emptyList(), records.folders())
            assertEquals(emptyList(), s.messages(id(2)))
            assertTrue(s.insert(received(2, 2)))
        }

    @Test
    fun theStoreSeesOnlyHexNamesAndNoPlaintext() =
        runTest {
            store().insert(received(1, 1, caption = "captionSECRET"))
            val all = records.snapshot()
            assertTrue(all.keys.all { it.length == 64 })
            assertTrue(all.values.all { folder -> folder.keys.all { it.length == 64 } })
            assertEquals(1, all.values.sumOf { it.size })
        }
}
