package app.gifitup.shared.domain.messages

import app.gifitup.shared.data.content.ContentCodec
import app.gifitup.shared.data.content.GifReference
import app.gifitup.shared.data.network.ApiError
import app.gifitup.shared.data.network.Session
import app.gifitup.shared.domain.auth.AuthResult
import app.gifitup.shared.domain.contacts.KeyBytes
import app.gifitup.shared.domain.groups.DeliveredMessage
import app.gifitup.shared.domain.groups.GroupChat
import app.gifitup.shared.domain.groups.IncomingSink
import app.gifitup.shared.domain.groups.SendObserver
import app.gifitup.shared.domain.groups.SyncOutcome
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MessagesTest {
    private val url = "https://static.klipy.com/ii/0123456789abcdef0123456789abcdef/ab/cd/funny_cat-1.webp"
    private val reference = assertNotNull(GifReference.of("klipy", "funny-cat", url, 320, 240))
    private val group = byteArrayOf(1, 2, 3)
    private val store = InMemoryMessageStore()
    private val intake = MessageIntake(store)

    private fun ref(n: Int) = ByteArray(32) { n.toByte() }

    private fun delivered(
        plaintext: ByteArray,
        n: Int = 1,
        groupId: ByteArray = group,
    ) = DeliveredMessage(groupId, n.toLong(), "iris", ByteArray(32), ref(n), plaintext)

    @Test
    fun aGifIsStoredWithItsCaption() =
        runTest {
            intake.deliver(delivered(assertNotNull(ContentCodec.encodeGif(reference, " hello "))))
            val stored = store.messages(KeyBytes(group)).single()
            val body = assertIs<MessageBody.Gif>(stored.body)
            assertEquals("hello", body.caption)
            assertEquals(url, body.reference.url)
            assertEquals("iris", stored.sender)
            assertNull(stored.state)
        }

    @Test
    fun badContentIsStoredAsAPlaceholderWithoutItsText() =
        runTest {
            intake.deliver(delivered("SECRET-MARKER not json".encodeToByteArray(), 1))
            intake.deliver(delivered("""{"v":2,"type":"gif"}""".encodeToByteArray(), 2))
            val bodies = store.messages(KeyBytes(group)).map { it.body }
            assertEquals(listOf(MessageBody.Unavailable, MessageBody.UpdateNeeded), bodies)
            assertFalse(store.messages(KeyBytes(group)).any { "SECRET-MARKER" in it.toString() })
        }

    @Test
    fun annotationsAreDroppedAndNothingIsStored() =
        runTest {
            intake.deliver(delivered("""{"v":1,"type":"ann_reaction","target":"x"}""".encodeToByteArray()))
            assertTrue(store.messages(KeyBytes(group)).isEmpty())
        }

    @Test
    fun theFirstMessageSeenWinsPerGroupAndReference() =
        runTest {
            val first = assertNotNull(ContentCodec.encodeGif(reference, "first"))
            val second = assertNotNull(ContentCodec.encodeGif(reference, "second"))
            intake.deliver(delivered(first, 1))
            intake.deliver(delivered(second, 1))
            intake.deliver(delivered(second, 1, groupId = byteArrayOf(9)))
            assertEquals("first", assertIs<MessageBody.Gif>(store.messages(KeyBytes(group)).single().body).caption)
            assertEquals(1, store.messages(KeyBytes(byteArrayOf(9))).size, "the same reference in another group is another message")
        }

    @Test
    fun theStoreIsBounded() =
        runTest {
            val content = assertNotNull(ContentCodec.encodeGif(reference, null))
            repeat(InMemoryMessageStore.MAX_PER_GROUP + 5) { n ->
                store.insert(
                    ChatMessage(
                        KeyBytes(group),
                        KeyBytes(n.toString().encodeToByteArray()),
                        "a",
                        n.toLong(),
                        MessageIntake.bodyOf(content)!!,
                        null,
                    ),
                )
            }
            val kept = store.messages(KeyBytes(group))
            assertEquals(InMemoryMessageStore.MAX_PER_GROUP, kept.size)
            assertEquals(5L, kept.first().seq, "the oldest are pushed out")
            // The group above holds one of the 50 slots.
            val results =
                (0 until InMemoryMessageStore.MAX_GROUPS + 2).map { n ->
                    store.insert(
                        ChatMessage(KeyBytes(byteArrayOf(100, n.toByte())), KeyBytes(ref(1)), "a", 1, MessageBody.Unavailable, null),
                    )
                }
            assertEquals(InMemoryMessageStore.MAX_GROUPS - 1, results.count { it }, "49 more groups fit, the rest are refused")
            assertFalse(results.last())
            assertTrue(store.messages(KeyBytes(byteArrayOf(100, (InMemoryMessageStore.MAX_GROUPS + 1).toByte()))).isEmpty())
        }

    @Test
    fun messageReferencesAreParsedStrictly() {
        val text = MessageRefs.format(KeyBytes(ref(7)))
        assertEquals(43, text.length)
        assertEquals(KeyBytes(ref(7)), MessageRefs.parse(text))
        for (bad in listOf("", text.dropLast(1), text + "A", text.dropLast(1) + "B", text.replace(text[0], '+'), "$text\n", " $text")) {
            assertNull(MessageRefs.parse(bad), bad)
        }
    }

    private class FakeChat(
        var outcome: SyncOutcome,
        var sealWith: ByteArray? = ByteArray(32) { 7 },
        var earlier: SyncOutcome? = null,
    ) : GroupChat {
        var sent: ByteArray? = null

        override suspend fun send(
            session: Session,
            groupId: ByteArray,
            text: ByteArray,
            sink: IncomingSink,
            observer: SendObserver,
        ): AuthResult<SyncOutcome> {
            sent = text
            earlier?.let { observer.earlierSettled(it) }
            if (earlier != null && earlier !is SyncOutcome.Sent) return AuthResult.Ok(SyncOutcome.PendingFirst(earlier!!))
            sealWith?.let { observer.sealed(it) }
            return AuthResult.Ok(outcome)
        }

        override suspend fun resume(
            session: Session,
            groupId: ByteArray,
            sink: IncomingSink,
        ): AuthResult<SyncOutcome> = AuthResult.Ok(outcome)
    }

    private val session =
        Session(
            "d",
            "t",
            1,
            checkNotNull(
                app.gifitup.shared.data.network.ApiEndpoint
                    .forOrigin("https://chat.example.test"),
            ),
        )
    private val sink = IncomingSink { }

    private suspend fun states() = store.messages(KeyBytes(group)).map { it.state }

    @Test
    fun aSentGifGoesFromSendingToSent() =
        runTest {
            val chat = FakeChat(SyncOutcome.Sent(12))
            SendGif(chat, store)(session, group, reference, " hi ", sink)
            val message = store.messages(KeyBytes(group)).single()
            assertEquals(DeliveryState.SENT, message.state)
            assertEquals(12L, message.seq)
            assertEquals("hi", assertIs<MessageBody.Gif>(message.body).caption)
            assertEquals(KeyBytes(ByteArray(32) { 7 }), message.reference)
            assertNull(message.sender)
            assertEquals(ContentCodec.encodeGif(reference, " hi ")!!.decodeToString(), chat.sent!!.decodeToString())
        }

    @Test
    fun anUnknownAnswerStaysSendingAndResumeSettlesIt() =
        runTest {
            val chat = FakeChat(SyncOutcome.InFlight(ApiError.NETWORK))
            val send = SendGif(chat, store)
            send(session, group, reference, null, sink)
            assertEquals(listOf(DeliveryState.SENDING), states())
            send.resume(session, group, sink)
            assertEquals(listOf(DeliveryState.SENDING), states(), "still no answer")
            chat.outcome = SyncOutcome.Sent(5)
            send.resume(session, group, sink)
            assertEquals(listOf(DeliveryState.SENT), states())
        }

    @Test
    fun anOlderMessageSettledFirstGetsItsStateBeforeTheNewOneIsSealed() =
        runTest {
            val chat = FakeChat(SyncOutcome.InFlight(ApiError.NETWORK))
            val send = SendGif(chat, store)
            send(session, group, reference, "first", sink)
            chat.earlier = SyncOutcome.Sent(4)
            chat.sealWith = ByteArray(32) { 8 }
            chat.outcome = SyncOutcome.Sent(5)
            send(session, group, reference, "second", sink)
            assertEquals(listOf(DeliveryState.SENT, DeliveryState.SENT), states())
            assertEquals(listOf(4L, 5L), store.messages(KeyBytes(group)).map { it.seq })
        }

    @Test
    fun anOlderMessageThatIsRefusedIsFailedAndTheNewOneIsNotShown() =
        runTest {
            val chat = FakeChat(SyncOutcome.InFlight(ApiError.NETWORK))
            val send = SendGif(chat, store)
            send(session, group, reference, "first", sink)
            chat.earlier = SyncOutcome.Rejected(ApiError.BAD_REQUEST)
            send(session, group, reference, "second", sink)
            assertEquals(listOf(DeliveryState.FAILED), states())
        }

    @Test
    fun aRefusedMessageIsFailed() =
        runTest {
            val chat = FakeChat(SyncOutcome.Rejected(ApiError.BAD_REQUEST))
            SendGif(chat, store)(session, group, reference, null, sink)
            assertEquals(listOf(DeliveryState.FAILED), states())
        }

    @Test
    fun nothingIsShownWhenNothingWasSealed() =
        runTest {
            val chat = FakeChat(SyncOutcome.Busy, sealWith = null)
            SendGif(chat, store)(session, group, reference, null, sink)
            assertTrue(states().isEmpty())
        }

    @Test
    fun aBadCaptionSendsNothing() =
        runTest {
            val chat = FakeChat(SyncOutcome.Sent(1))
            val result = SendGif(chat, store)(session, group, reference, "hi\n", sink)
            assertEquals(SyncOutcome.InvalidRequest, assertIs<AuthResult.Ok<SyncOutcome>>(result).value)
            assertNull(chat.sent)
            assertTrue(states().isEmpty())
        }
}
