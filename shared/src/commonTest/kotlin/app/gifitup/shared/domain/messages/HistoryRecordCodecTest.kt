package app.gifitup.shared.domain.messages

import app.gifitup.shared.data.content.GifReference
import app.gifitup.shared.domain.contacts.KeyBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class HistoryRecordCodecTest {
    private val url = "https://static.klipy.com/ii/0123456789abcdef0123456789abcdef/ab/cd/funny_cat-1.webp"
    private val gif = assertNotNull(GifReference.of("klipy", "funny-cat", url, 320, 240))
    private val group = KeyBytes(ByteArray(16) { 1 })
    private val ref = KeyBytes(ByteArray(32) { 2 })

    private fun message(
        body: MessageBody,
        sender: String? = "bob",
        state: DeliveryState? = null,
    ) = StoredMessage(ChatMessage(group, ref, sender, 4L, body, state, 123L), 9L)

    @Test
    fun everyKindOfMessageRoundTrips() {
        val all =
            listOf(
                message(MessageBody.Gif(gif, "hello 👋 ✓")),
                message(MessageBody.Gif(gif, null)),
                message(MessageBody.GroupMeta("Friday crew")),
                message(MessageBody.Unavailable),
                message(MessageBody.UpdateNeeded),
                message(MessageBody.Gif(gif, "mine"), sender = null, state = DeliveryState.FAILED),
            )
        for (stored in all) {
            val back = assertNotNull(HistoryRecordCodec.decode(HistoryRecordCodec.encode(stored)))
            assertEquals(stored.arrival, back.arrival)
            assertEquals(stored.message.sender, back.message.sender)
            assertEquals(stored.message.state, back.message.state)
            assertEquals(stored.message.seq, back.message.seq)
            assertEquals(stored.message.at, back.message.at)
            assertEquals(stored.message.reference, back.message.reference)
            assertEquals(stored.message.body::class, back.message.body::class)
        }
        val gifBack = HistoryRecordCodec.decode(HistoryRecordCodec.encode(all[0]))!!.message.body
        assertEquals(gif, assertIs<MessageBody.Gif>(gifBack).reference)
        assertEquals("hello 👋 ✓", gifBack.caption)
    }

    @Test
    fun aRecordWithAnyCutOrAnExtraByteDoesNotRead() {
        val bytes = HistoryRecordCodec.encode(message(MessageBody.Gif(gif, "caption")))
        for (length in bytes.indices) assertNull(HistoryRecordCodec.decode(bytes.copyOf(length)), "cut at $length")
        assertNull(HistoryRecordCodec.decode(bytes + byteArrayOf(0)))
        assertNull(HistoryRecordCodec.decode(ByteArray(0)))
    }

    @Test
    fun aGifThatNoLongerPassesTheRulesDoesNotRead() {
        val bytes = HistoryRecordCodec.encode(message(MessageBody.Gif(gif, null)))
        val text = bytes.decodeToString()
        val bad = text.replace("klipy.com", "klipy.xyz").encodeToByteArray()
        assertNull(HistoryRecordCodec.decode(bad))
    }

    @Test
    fun aSenderWithoutAStateOrAStateWithoutASenderDoesNotRead() {
        assertNull(HistoryRecordCodec.decode(HistoryRecordCodec.encode(message(MessageBody.Unavailable, sender = null, state = null))))
        assertNull(
            HistoryRecordCodec.decode(
                HistoryRecordCodec.encode(message(MessageBody.Unavailable, sender = "bob", state = DeliveryState.SENT)),
            ),
        )
    }

    @Test
    fun invalidUtf8AndUnknownVersionsAndTagsDoNotRead() {
        val bytes = HistoryRecordCodec.encode(message(MessageBody.GroupMeta("name")))
        assertNull(HistoryRecordCodec.decode(bytes.copyOf().also { it[0] = 2 }))
        val withBadUtf8 = bytes.copyOf().also { it[it.size - 1] = 0xFF.toByte() }
        assertNull(HistoryRecordCodec.decode(withBadUtf8))
        val unknownTag = bytes.copyOf().also { it[it.size - 1 - "name".length - 4] = 9 }
        assertNull(HistoryRecordCodec.decode(unknownTag))
    }
}
