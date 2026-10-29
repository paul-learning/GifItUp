package app.gifitup.mls

import app.gifitup.shared.data.content.GifReference
import app.gifitup.shared.data.storage.InMemoryRecordStore
import app.gifitup.shared.domain.contacts.KeyBytes
import app.gifitup.shared.domain.messages.ChatMessage
import app.gifitup.shared.domain.messages.DeliveryState
import app.gifitup.shared.domain.messages.EncryptedMessageStore
import app.gifitup.shared.domain.messages.MessageBody
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The message history over the **real** cipher (uniffi on the JVM, wasm in Chrome): what the store keeps survives a new
 * store and the same key, can't be read without it, shows no plaintext to the record store, and a changed record is
 * counted and left alone (ADR-0017).
 */
class EncryptedHistoryContractTest {
    private val origin = "https://chat.example.test"
    private val url = "https://static.klipy.com/ii/0123456789abcdef0123456789abcdef/ab/cd/funny_cat-1.webp"
    private val gif = assertNotNull(GifReference.of("klipy", "funny-cat", url, 320, 240))
    private val group = KeyBytes(ByteArray(16) { 1 })
    private val records = InMemoryRecordStore()

    private fun key(byte: Int = 7) = ByteArray(32) { byte.toByte() }

    private suspend fun store(byte: Int = 7) = EncryptedMessageStore(platformHistoryCipherFactory().create(key(byte), origin), records)

    private fun message(
        n: Int,
        caption: String,
    ) = ChatMessage(group, KeyBytes(ByteArray(32) { n.toByte() }), "bob", null, MessageBody.Gif(gif, caption), null, 1_000L + n)

    @Test
    fun theHistorySurvivesANewStoreWithTheSameKey() =
        runTest {
            val first = store()
            assertTrue(first.insert(message(1, "hello 👋")))
            assertTrue(
                first.insert(
                    ChatMessage(
                        group,
                        KeyBytes(
                            ByteArray(32) {
                                2
                            },
                        ),
                        null,
                        null,
                        MessageBody.Gif(gif, "mine"),
                        DeliveryState.SENDING,
                        5L,
                    ),
                ),
            )
            first.setState(group, KeyBytes(ByteArray(32) { 2 }), DeliveryState.SENT, 9L)
            val again = store().messages(group)
            assertEquals(2, again.size)
            assertEquals("hello 👋", assertIs<MessageBody.Gif>(again[0].body).caption)
            assertEquals(DeliveryState.SENT, again[1].state)
            assertEquals(9L, again[1].seq)
        }

    @Test
    fun theRecordStoreSeesNoPlaintextAndOnlySizeBuckets() =
        runTest {
            store().insert(message(1, "captionSECRET"))
            store().insert(message(2, "x"))
            val all = records.snapshot()
            val sizes = all.values.flatMap { folder -> folder.values.map { it.size } }
            assertEquals(1, sizes.distinct().size, "two different messages, the same bucket, the same size")
            for ((folder, inside) in all) {
                assertEquals(64, folder.length)
                for ((name, bytes) in inside) {
                    assertEquals(64, name.length)
                    assertTrue(!bytes.decodeToString().contains("SECRET") && !bytes.decodeToString().contains("klipy"))
                }
            }
        }

    @Test
    fun anotherKeyFindsNothingAndTheRecordsStay() =
        runTest {
            store().insert(message(1, "a"))
            assertEquals(emptyList(), store(8).messages(group))
            assertEquals(1, store().messages(group).size)
        }

    @Test
    fun aChangedRecordIsCountedAndTheOthersStillRead() =
        runTest {
            store().insert(message(1, "a"))
            store().insert(message(2, "b"))
            val folder = records.folders().single()
            val (name, bytes) =
                records
                    .snapshot()
                    .getValue(folder)
                    .entries
                    .first()
            records.put(folder, name, bytes.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() })
            val reopened = store()
            assertEquals(1, reopened.messages(group).size)
            assertEquals(1, reopened.unreadable(group))
            assertEquals(2, records.list(folder).size)
        }
}
