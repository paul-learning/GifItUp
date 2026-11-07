package app.gifitup.shared.domain.messages

import app.gifitup.shared.data.content.Content
import app.gifitup.shared.data.content.ContentCodec
import app.gifitup.shared.data.content.Decoded
import app.gifitup.shared.data.content.GifReference
import app.gifitup.shared.data.network.ApiError
import app.gifitup.shared.data.network.Session
import app.gifitup.shared.domain.auth.AuthResult
import app.gifitup.shared.domain.contacts.KeyBytes
import app.gifitup.shared.domain.groups.DeliveredMessage
import app.gifitup.shared.domain.groups.GroupChat
import app.gifitup.shared.domain.groups.IncomingSink
import app.gifitup.shared.domain.groups.MemberChange
import app.gifitup.shared.domain.groups.MembershipChange
import app.gifitup.shared.domain.groups.SendObserver
import app.gifitup.shared.domain.groups.SendState
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
    private companion object {
        const val NOW = 1_800_000_000L
    }

    private val url = "https://static.klipy.com/ii/0123456789abcdef0123456789abcdef/ab/cd/funny_cat-1.webp"
    private val reference = assertNotNull(GifReference.of("klipy", "funny-cat", url, 320, 240))
    private val group = byteArrayOf(1, 2, 3)
    private val store = InMemoryMessageStore()
    private val intake = MessageIntake(store) { NOW }

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
                        NOW,
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
                        ChatMessage(KeyBytes(byteArrayOf(100, n.toByte())), KeyBytes(ref(1)), "a", 1, MessageBody.Unavailable, null, NOW),
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

    /** Plays the engine's part: it keeps the pending and last accepted reference the way the stored state does. */
    private class FakeChat(
        var outcome: SyncOutcome,
        var sealWith: ByteArray? = ByteArray(32) { 7 },
        var earlier: SyncOutcome? = null,
    ) : GroupChat {
        var sent: ByteArray? = null
        var pending: ByteArray? = null
        var lastSent: ByteArray? = null
        var lastSentSeq: Long? = null
        var pendingUnknown = false

        private fun state() = SendState(pending, lastSent, lastSentSeq, pendingUnknown)

        private fun answered(
            reference: ByteArray?,
            answer: SyncOutcome,
        ) {
            if (answer is SyncOutcome.Sent && reference != null) {
                lastSent = reference
                lastSentSeq = answer.seq
            }
            if (answer is SyncOutcome.Sent || answer is SyncOutcome.Rejected) pending = null
        }

        @Suppress("ReturnCount") // The engine's early exits, in order.
        override suspend fun send(
            session: Session,
            groupId: ByteArray,
            text: ByteArray,
            sink: IncomingSink,
            observer: SendObserver,
        ): AuthResult<SyncOutcome> {
            sent = text
            observer.reconcile(state())
            val older = earlier
            if (older != null) {
                val ref = pending
                observer.earlierSettled(ref, older)
                answered(ref, older)
                if (older !is SyncOutcome.Sent) return AuthResult.Ok(SyncOutcome.PendingFirst(older))
            }
            val ref = sealWith ?: return AuthResult.Ok(outcome)
            observer.sealed(ref)
            pending = ref
            answered(ref, outcome)
            return AuthResult.Ok(outcome)
        }

        override suspend fun resume(
            session: Session,
            groupId: ByteArray,
            sink: IncomingSink,
            observer: SendObserver,
        ): AuthResult<SyncOutcome> {
            observer.reconcile(state())
            answered(pending, outcome)
            return AuthResult.Ok(outcome)
        }
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
            SendGif(chat, store) { NOW }(session, group, reference, " hi ", sink)
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
            val send = SendGif(chat, store) { NOW }
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
            val send = SendGif(chat, store) { NOW }
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
            val send = SendGif(chat, store) { NOW }
            send(session, group, reference, "first", sink)
            chat.earlier = SyncOutcome.Rejected(ApiError.BAD_REQUEST)
            send(session, group, reference, "second", sink)
            assertEquals(listOf(DeliveryState.FAILED), states())
        }

    @Test
    fun aMessageForAGroupWithNoRoomInTheStoreIsSentButNotShown() =
        runTest {
            repeat(InMemoryMessageStore.MAX_GROUPS) { n ->
                store.insert(
                    ChatMessage(KeyBytes(byteArrayOf(100, n.toByte())), KeyBytes(ref(1)), "a", 1, MessageBody.Unavailable, null, NOW),
                )
            }
            val chat = FakeChat(SyncOutcome.Sent(3))
            val result = SendGif(chat, store) { NOW }(session, group, reference, null, sink)
            assertEquals(SyncOutcome.Sent(3), assertIs<AuthResult.Ok<SyncOutcome>>(result).value)
            assertNotNull(chat.sent, "it was sent")
            assertTrue(states().isEmpty(), "and not shown")
        }

    @Test
    fun aRefusedMessageIsFailed() =
        runTest {
            val chat = FakeChat(SyncOutcome.Rejected(ApiError.BAD_REQUEST))
            SendGif(chat, store) { NOW }(session, group, reference, null, sink)
            assertEquals(listOf(DeliveryState.FAILED), states())
        }

    @Test
    fun nothingIsShownWhenNothingWasSealed() =
        runTest {
            val chat = FakeChat(SyncOutcome.Busy, sealWith = null)
            SendGif(chat, store) { NOW }(session, group, reference, null, sink)
            assertTrue(states().isEmpty())
        }

    @Test
    fun aBadCaptionSendsNothing() =
        runTest {
            val chat = FakeChat(SyncOutcome.Sent(1))
            val result = SendGif(chat, store) { NOW }(session, group, reference, "hi\n", sink)
            assertEquals(SyncOutcome.InvalidRequest, assertIs<AuthResult.Ok<SyncOutcome>>(result).value)
            assertNull(chat.sent)
            assertTrue(states().isEmpty())
        }

    @Test
    fun aPendingMessageWithoutAReferenceLeavesTheSendingRecordsAlone() =
        runTest {
            val chat = FakeChat(SyncOutcome.InFlight(ApiError.NETWORK))
            val send = SendGif(chat, store) { NOW }
            send(session, group, reference, "first", sink)
            chat.pendingUnknown = true
            chat.pending = null
            send.resume(session, group, sink)
            assertEquals(listOf(DeliveryState.SENDING), states(), "nothing could be told apart, so nothing was failed")
        }

    @Test
    fun aRosterChangeBecomesOneNoticePerLeafAndTheSameChangeTwiceIsOne() =
        runTest {
            val change =
                MembershipChange(
                    group,
                    5L,
                    "bob",
                    listOf(MemberChange("carol", 2), MemberChange("dave", 3)),
                    listOf(MemberChange("erin", 1)),
                )
            intake.membership(change)
            intake.membership(change)
            val notices = store.messages(KeyBytes(group)).map { it.body }.filterIsInstance<MessageBody.Membership>()
            assertEquals(3, notices.size)
            assertEquals(listOf(MembershipEvent.ADDED, MembershipEvent.ADDED, MembershipEvent.REMOVED), notices.map { it.event })
            assertEquals(listOf("carol", "dave", "erin"), notices.map { it.subject })
            assertTrue(notices.all { it.by == "bob" })
        }

    @Test
    fun aJoinIsOneNoticeWithTheSizeAndAnInvalidNameBecomesAFixedTextNoticeNotASilentDrop() =
        runTest {
            intake.membership(MembershipChange(group, 0L, "bob", emptyList(), emptyList(), joined = 4))
            val joined = store.messages(KeyBytes(group)).single().body
            assertEquals(4, assertIs<MessageBody.Membership>(joined).count)
            intake.membership(MembershipChange(group, 6L, "bob", listOf(MemberChange("Not A Name", 2)), emptyList()))
            val all = store.messages(KeyBytes(group)).map { it.body }.filterIsInstance<MessageBody.Membership>()
            assertEquals(
                listOf(MembershipEvent.JOINED, MembershipEvent.CHANGED),
                all.map { it.event },
                "the change is still shown, without the name",
            )
            assertEquals("", all.last().subject)
            // The sender's name is invalid too: the notice names nobody at all.
            intake.membership(MembershipChange(group, 7L, "Bad Name", listOf(MemberChange("carol", 2)), emptyList()))
            val last = store.messages(KeyBytes(group)).last().body as MessageBody.Membership
            assertEquals(MembershipEvent.CHANGED, last.event)
            assertNull(last.by)
            // The same join delivered again is one notice; a join at a later join point (a re-add) is a new one.
            intake.membership(MembershipChange(group, 0L, "bob", emptyList(), emptyList(), joined = 3))
            intake.membership(MembershipChange(group, 12L, "bob", emptyList(), emptyList(), joined = 3))
            assertEquals(2, store.messages(KeyBytes(group)).count { (it.body as? MessageBody.Membership)?.event == MembershipEvent.JOINED })
        }

    @Test
    fun aNoticeReferenceNeverLooksLikeAMessageReference() {
        val ref = MessageIntake.noticeRef(MembershipEvent.ADDED, 5L, 2)
        assertEquals(0xFE.toByte(), ref.bytes[0])
        assertEquals(32, ref.bytes.size)
        assertFalse(ref == MessageIntake.noticeRef(MembershipEvent.REMOVED, 5L, 2))
        assertFalse(ref == MessageIntake.noticeRef(MembershipEvent.ADDED, 6L, 2))
        assertFalse(ref == MessageIntake.noticeRef(MembershipEvent.ADDED, 5L, 3))
    }

    @Test
    fun aChangeThisDeviceMadeIsANoticeOfOursWithNoSender() =
        runTest {
            intake.membership(MembershipChange(group, 3L, null, listOf(MemberChange("carol", 0)), listOf(MemberChange("erin", 0))))
            val own = store.messages(KeyBytes(group))
            assertEquals(2, own.size)
            assertTrue(own.all { it.sender == null && it.state == DeliveryState.SENT })
            assertEquals(listOf(MembershipEvent.ADDED, MembershipEvent.REMOVED), own.map { (it.body as MessageBody.Membership).event })
            assertNull((own.first().body as MessageBody.Membership).by)
        }

    private val heart = "\u2764\uFE0F"

    private suspend fun storeGif(n: Int): KeyBytes {
        intake.deliver(delivered(assertNotNull(ContentCodec.encodeGif(reference, null)), n))
        return KeyBytes(ref(n))
    }

    @Test
    fun aReactionIsAppliedToItsGifUnderTheSendersCredentialNameAndIsNotAMessage() =
        runTest {
            val target = storeGif(1)
            intake.deliver(delivered(assertNotNull(ContentCodec.encodeReaction(target, heart)), 2))
            val messages = store.messages(KeyBytes(group))
            assertEquals(1, messages.size, "a reaction is not a message")
            assertEquals(listOf("iris" to heart), messages.single().reactions.map { it.by to it.emoji })
            intake.deliver(delivered(assertNotNull(ContentCodec.encodeReaction(target, null)), 3))
            assertEquals(emptyList(), store.messages(KeyBytes(group)).single().reactions)
        }

    @Test
    fun aReactionToNothingOrToANonGifIsAcknowledgedAndChangesNothing() =
        runTest {
            val target = storeGif(1)
            intake.deliver(delivered(assertNotNull(ContentCodec.encodeGroupMeta("Trip")), 2))
            val rename = KeyBytes(ref(2))
            intake.deliver(delivered(assertNotNull(ContentCodec.encodeReaction(KeyBytes(ref(77)), heart)), 3))
            intake.deliver(delivered(assertNotNull(ContentCodec.encodeReaction(rename, heart)), 4))
            intake.deliver(delivered(assertNotNull(ContentCodec.encodeReaction(target, heart)), 5, groupId = byteArrayOf(9)))
            val messages = store.messages(KeyBytes(group))
            assertEquals(2, messages.size)
            assertEquals(emptyList(), messages.flatMap { it.reactions })
            assertEquals(emptyList(), store.messages(KeyBytes(byteArrayOf(9))))
        }

    @Test
    fun aReactionWithAnEmojiOutsideTheTableIsDroppedBeforeTheStoreSeesIt() =
        runTest {
            val target = storeGif(1)
            val bad = """{"v":1,"type":"ann_reaction","target":"${MessageRefs.format(target)}","emoji":"x"}"""
            intake.deliver(delivered(bad.encodeToByteArray(), 2))
            assertEquals(emptyList(), store.messages(KeyBytes(group)).single().reactions)
            assertEquals(1, store.messages(KeyBytes(group)).size)
        }

    private val own = "me_user"

    private fun sendGif(chat: FakeChat) = SendGif(chat, store) { NOW }

    @Test
    fun aReactionIsAppliedOnlyOnceTheServerAcceptedItAndMakesNoMessage() =
        runTest {
            val target = storeGif(1)
            val chat = FakeChat(SyncOutcome.Sent(9))
            val result = sendGif(chat).react(session, group, target, heart, own, sink)
            assertEquals(SyncOutcome.Sent(9), (result as AuthResult.Ok).value)
            val messages = store.messages(KeyBytes(group))
            assertEquals(1, messages.size, "a reaction is not a message and has no sending record")
            assertEquals(listOf(own to heart), messages.single().reactions.map { it.by to it.emoji })
            val sent = assertIs<Decoded.Message>(ContentCodec.decode(assertNotNull(chat.sent)))
            assertEquals(target, assertIs<Content.Reaction>(sent.content).target)
        }

    @Test
    fun aReactionWithAnUnknownAnswerIsAppliedWhenTheResumeIsAccepted() =
        runTest {
            val target = storeGif(1)
            val chat = FakeChat(SyncOutcome.Unavailable(ApiError.NETWORK))
            val sender = sendGif(chat)
            sender.react(session, group, target, heart, own, sink)
            assertEquals(emptyList(), store.messages(KeyBytes(group)).single().reactions, "not yet")
            chat.outcome = SyncOutcome.Sent(10)
            sender.resume(session, group, sink)
            assertEquals(
                listOf(own to heart),
                store
                    .messages(KeyBytes(group))
                    .single()
                    .reactions
                    .map { it.by to it.emoji },
            )
        }

    @Test
    fun aRefusedReactionIsForgottenAndNeverShows() =
        runTest {
            val target = storeGif(1)
            val chat = FakeChat(SyncOutcome.Rejected(ApiError.NETWORK))
            val sender = sendGif(chat)
            sender.react(session, group, target, heart, own, sink)
            chat.outcome = SyncOutcome.Sent(11)
            sender.resume(session, group, sink)
            assertEquals(emptyList(), store.messages(KeyBytes(group)).single().reactions)
        }

    @Test
    fun aReactionThatWouldChangeNothingSendsNothing() =
        runTest {
            val target = storeGif(1)
            val chat = FakeChat(SyncOutcome.Sent(9))
            val sender = sendGif(chat)

            fun invalid(result: AuthResult<SyncOutcome>) = assertEquals(SyncOutcome.InvalidRequest, (result as AuthResult.Ok).value)
            invalid(sender.react(session, group, KeyBytes(ref(77)), heart, own, sink))
            invalid(sender.react(session, group, target, "x", own, sink))
            invalid(sender.react(session, group, target, null, own, sink))
            assertNull(chat.sent, "nothing reached the engine")
            sender.react(session, group, target, heart, own, sink)
            chat.sent = null
            invalid(sender.react(session, group, target, heart, own, sink))
            assertNull(chat.sent)
        }

    @Test
    fun aReactionCanBeTakenBack() =
        runTest {
            val target = storeGif(1)
            val chat = FakeChat(SyncOutcome.Sent(9), sealWith = ByteArray(32) { 50 })
            val sender = sendGif(chat)
            sender.react(session, group, target, heart, own, sink)
            chat.sealWith = ByteArray(32) { 51 }
            sender.react(session, group, target, null, own, sink)
            assertEquals(emptyList(), store.messages(KeyBytes(group)).single().reactions)
        }

    @Test
    fun aReactionTheEngineSaysWasAcceptedIsAppliedByTheNextReconcile() =
        runTest {
            val target = storeGif(1)
            val chat = FakeChat(SyncOutcome.Unavailable(ApiError.NETWORK), sealWith = ByteArray(32) { 50 })
            val sender = sendGif(chat)
            sender.react(session, group, target, heart, own, sink)
            assertEquals(emptyList(), store.messages(KeyBytes(group)).single().reactions, "unknown answer: not yet")
            // The engine's own state: nothing pending, the reaction was the last accepted send.
            chat.pending = null
            chat.lastSent = ByteArray(32) { 50 }
            chat.lastSentSeq = 12L
            sender.resume(session, group, sink)
            assertEquals(
                listOf(own to heart),
                store
                    .messages(KeyBytes(group))
                    .single()
                    .reactions
                    .map { it.by to it.emoji },
            )
        }

    @Test
    fun aReactionThatIsNeitherPendingNorTheLastAcceptedIsForgottenByTheReconcile() =
        runTest {
            val target = storeGif(1)
            val chat = FakeChat(SyncOutcome.Unavailable(ApiError.NETWORK), sealWith = ByteArray(32) { 50 })
            val sender = sendGif(chat)
            sender.react(session, group, target, heart, own, sink)
            chat.pending = null
            chat.lastSent = ByteArray(32) { 99 } // some other message was the last accepted one
            chat.lastSentSeq = 13L
            sender.resume(session, group, sink)
            // Even if the engine later reports the old reference as accepted, the reaction is no longer remembered.
            chat.lastSent = ByteArray(32) { 50 }
            chat.lastSentSeq = 14L
            sender.resume(session, group, sink)
            assertEquals(emptyList(), store.messages(KeyBytes(group)).single().reactions)
        }

    /** A store whose first reaction write fails, like a record store that cannot write once. */
    private class FlakyReactions(
        private val inner: InMemoryMessageStore,
    ) : MessageStore by inner {
        var failNext = true

        override suspend fun react(
            groupId: KeyBytes,
            target: KeyBytes,
            by: String,
            emoji: String?,
        ): Boolean {
            if (failNext) {
                failNext = false
                throw HistoryStoreException(HistoryStoreException.Kind.STORAGE)
            }
            return inner.react(groupId, target, by, emoji)
        }
    }

    @Test
    fun anAcceptedReactionWhoseWriteFailedIsAppliedByTheNextReconcile() =
        runTest {
            val target = storeGif(1)
            val flaky = FlakyReactions(store)
            val chat = FakeChat(SyncOutcome.Sent(9), sealWith = ByteArray(32) { 50 })
            val sender = SendGif(chat, flaky) { NOW }
            sender.react(session, group, target, heart, own, sink)
            assertEquals(emptyList(), store.messages(KeyBytes(group)).single().reactions, "the write failed")
            sender.resume(session, group, sink)
            assertEquals(
                listOf(own to heart),
                store
                    .messages(KeyBytes(group))
                    .single()
                    .reactions
                    .map { it.by to it.emoji },
            )
        }

    @Test
    fun aReactionThatIsSettledFirstByTheNextSendIsApplied() =
        runTest {
            val target = storeGif(1)
            val chat = FakeChat(SyncOutcome.Unavailable(ApiError.NETWORK), sealWith = ByteArray(32) { 50 })
            val sender = sendGif(chat)
            sender.react(session, group, target, heart, own, sink)
            chat.earlier = SyncOutcome.Sent(10)
            chat.sealWith = ByteArray(32) { 51 }
            chat.outcome = SyncOutcome.Sent(11)
            sender(session, group, reference, "next", sink)
            val messages = store.messages(KeyBytes(group))
            assertEquals(2, messages.size, "the GIF and the new message")
            assertEquals(listOf(own to heart), messages.first().reactions.map { it.by to it.emoji })
        }
}
