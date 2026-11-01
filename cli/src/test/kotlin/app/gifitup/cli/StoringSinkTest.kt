package app.gifitup.cli

import app.gifitup.shared.data.content.ContentCodec
import app.gifitup.shared.domain.groups.DeliveredMessage
import app.gifitup.shared.domain.messages.ChatMessage
import app.gifitup.shared.domain.messages.DeliveryState
import app.gifitup.shared.domain.messages.HistoryStoreException
import app.gifitup.shared.domain.messages.InMemoryMessageStore
import app.gifitup.shared.domain.messages.MessageIntake
import app.gifitup.shared.domain.messages.MessageStore
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class StoringSinkTest {
    private val bytes = ByteArrayOutputStream()
    private val out = PrintStream(bytes)
    private val message =
        DeliveredMessage(
            ByteArray(16) { 1 },
            7,
            "e2e_ann",
            ByteArray(32),
            ByteArray(32) { 3 },
            ContentCodec.encodeGif(TEST_GIF, "hi")!!,
        )

    @Test
    fun aMessageIsStoredAndThenPrinted() =
        runBlocking {
            val store = InMemoryMessageStore()
            StoringSink(MessageIntake(store) { 1L }, out).deliver(message)
            assertEquals(
                1,
                store
                    .messages(
                        app.gifitup.shared.domain.contacts
                            .KeyBytes(message.groupId),
                    ).size,
            )
            assertEquals("message 7 from e2e_ann: gif e2e-test-gif \"hi\"\n", bytes.toString())
        }

    @Test
    fun aFailingStoreStopsTheEngineAndPrintsNothingOfTheMessage() =
        runBlocking {
            val failing =
                object : MessageStore by InMemoryMessageStore() {
                    override suspend fun insert(message: ChatMessage): Boolean =
                        throw HistoryStoreException(HistoryStoreException.Kind.STORAGE)
                }
            assertFailsWith<HistoryStoreException> { StoringSink(MessageIntake(failing) { 1L }, out).deliver(message) }
            assertTrue(bytes.toString().isEmpty(), "no line for a message that was not stored")
        }

    @Test
    fun aStoredLineShowsSeqSenderStateAndText() {
        val own =
            ChatMessage(
                app.gifitup.shared.domain.contacts
                    .KeyBytes(ByteArray(16)),
                app.gifitup.shared.domain.contacts
                    .KeyBytes(ByteArray(32)),
                null,
                3,
                app.gifitup.shared.domain.messages.MessageBody.UpdateNeeded,
                DeliveryState.SENT,
                1L,
            )
        assertEquals("stored 3 - sent: update needed", storedLine(own))
    }
}
