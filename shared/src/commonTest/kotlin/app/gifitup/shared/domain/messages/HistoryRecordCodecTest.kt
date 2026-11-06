package app.gifitup.shared.domain.messages

import app.gifitup.shared.data.content.ContentCodec
import app.gifitup.shared.data.content.GifReference
import app.gifitup.shared.data.content.ReactionEmojis
import app.gifitup.shared.domain.contacts.KeyBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
                message(assertNotNull(MessageBody.Membership.of(MembershipEvent.ADDED, "carol", "bob"))),
                message(assertNotNull(MessageBody.Membership.of(MembershipEvent.JOINED, "", "bob", 4))),
                message(assertNotNull(MessageBody.Membership.of(MembershipEvent.CHANGED, "", null))),
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
        // Such a message is refused when it is encoded; a stored one with the state byte changed is refused when read.
        val received = HistoryRecordCodec.encode(message(MessageBody.Unavailable))
        val receivedState = 1 + 4 + 16 + 32 + 8 + 1 + 4 + 3 + 1 + 8
        assertNull(HistoryRecordCodec.decode(received.copyOf().also { it[receivedState] = 2 }), "a received message has no state")
        val own = HistoryRecordCodec.encode(message(MessageBody.Unavailable, sender = null, state = DeliveryState.FAILED))
        val ownState = 1 + 4 + 16 + 32 + 8 + 1 + 1 + 8
        assertNull(HistoryRecordCodec.decode(own.copyOf().also { it[ownState] = 0 }), "one of ours has a state")
        assertFailsWith<IllegalStateException> {
            HistoryRecordCodec.encode(message(MessageBody.Unavailable, sender = "bob", state = DeliveryState.SENT))
        }
        assertFailsWith<IllegalStateException> { HistoryRecordCodec.encode(message(MessageBody.Unavailable, sender = null, state = null)) }
    }

    @Test
    fun invalidUtf8AndUnknownVersionsAndTagsDoNotRead() {
        val bytes = HistoryRecordCodec.encode(message(MessageBody.GroupMeta("name")))
        assertNull(HistoryRecordCodec.decode(bytes.copyOf().also { it[0] = 3 }), "a newer version than 2")
        assertNull(HistoryRecordCodec.decode(bytes.copyOf().also { it[0] = 0 }))
        // The last byte of a record is the reactions count (version 2); the name ends one byte before it.
        val withBadUtf8 = bytes.copyOf().also { it[it.size - 2] = 0xFF.toByte() }
        assertNull(HistoryRecordCodec.decode(withBadUtf8))
        val unknownTag = bytes.copyOf().also { it[it.size - 2 - "name".length - 4] = 9 }
        assertNull(HistoryRecordCodec.decode(unknownTag))
    }

    @Test
    fun strictFieldsAreRefused() {
        val stored = message(MessageBody.Unavailable)
        val bytes = HistoryRecordCodec.encode(stored)
        // version(1) group(4+16) reference(32) arrival(8) sender flag(1) sender(4+3) seq flag(1)
        val seqFlag = 1 + 4 + 16 + 32 + 8 + 1 + 4 + 3
        assertNull(HistoryRecordCodec.decode(bytes.copyOf().also { it[seqFlag] = 2 }), "a seq flag is 0 or 1")
        val negativeArrival = bytes.copyOf().also { it[1 + 4 + 16 + 32] = 0x80.toByte() }
        assertNull(HistoryRecordCodec.decode(negativeArrival), "an arrival number is not negative")
        val stateByte = seqFlag + 1 + 8
        assertNull(HistoryRecordCodec.decode(bytes.copyOf().also { it[stateByte] = 9 }), "no such delivery state")
    }

    @Test
    fun theStateIsStoredAsFixedCodesNotAsEnumOrdinals() {
        val own = HistoryRecordCodec.encode(message(MessageBody.Unavailable, sender = null, state = DeliveryState.FAILED))
        val stateByte = 1 + 4 + 16 + 32 + 8 + 1 + 1 + 8
        assertEquals(3, own[stateByte].toInt())
        val sent = HistoryRecordCodec.encode(message(MessageBody.Unavailable, sender = null, state = DeliveryState.SENT))
        assertEquals(2, sent[stateByte].toInt())
    }

    @Test
    fun aMembershipNoticeKeepsItsFieldsAndABadOneIsRefusedAtTheDoor() {
        val notice = assertNotNull(MessageBody.Membership.of(MembershipEvent.REMOVED, "carol", "bob"))
        val back = assertIs<MessageBody.Membership>(HistoryRecordCodec.decode(HistoryRecordCodec.encode(message(notice)))!!.message.body)
        assertEquals(MembershipEvent.REMOVED, back.event)
        assertEquals("carol", back.subject)
        assertEquals("bob", back.by)
        assertNull(MessageBody.Membership.of(MembershipEvent.ADDED, "Carol Smith", "bob"), "a name that breaks the username rule")
        assertNull(MessageBody.Membership.of(MembershipEvent.ADDED, "carol", "bo b"))
        assertNull(MessageBody.Membership.of(MembershipEvent.JOINED, "carol", "bob", 3), "a joined notice names nobody")
        assertNull(MessageBody.Membership.of(MembershipEvent.JOINED, "", "bob", 0), "and has a size")
        assertNull(MessageBody.Membership.of(MembershipEvent.ADDED, "carol", "bob", 2), "an added notice has no size")
    }

    private val heart = "\u2764\uFE0F"
    private val thumbsUp = "\uD83D\uDC4D"

    private fun gifWith(vararg reactions: Reaction) =
        StoredMessage(ChatMessage(group, ref, "bob", 4L, MessageBody.Gif(gif, "hi"), null, 123L, reactions.toList()), 9L)

    @Test
    fun theReactionsOfAGifRoundTripInOrder() {
        val back =
            assertNotNull(
                HistoryRecordCodec.decode(HistoryRecordCodec.encode(gifWith(Reaction("iris", heart), Reaction("cy_2", thumbsUp)))),
            )
        assertEquals(listOf("iris" to heart, "cy_2" to thumbsUp), back.message.reactions.map { it.by to it.emoji })
    }

    @Test
    fun aVersionOneRecordStillReadsWithNoReactions() {
        val v2 = HistoryRecordCodec.encode(gifWith())
        // Version 1 is the same layout without the trailing count.
        val v1 = v2.copyOf(v2.size - 1).also { it[0] = 1 }
        assertEquals(emptyList(), assertNotNull(HistoryRecordCodec.decode(v1)).message.reactions)
    }

    @Test
    fun reactionsThatBreakARuleDoNotRead() {
        val good = HistoryRecordCodec.encode(gifWith(Reaction("iris", heart)))
        assertNotNull(HistoryRecordCodec.decode(good))
        // Written by hand: the codec's own encoder refuses nothing about names and emojis, the reader does.
        val badEmoji = HistoryRecordCodec.encode(gifWith(Reaction("iris", "x")))
        assertNull(HistoryRecordCodec.decode(badEmoji))
        val badName = HistoryRecordCodec.encode(gifWith(Reaction("Iris!", heart)))
        assertNull(HistoryRecordCodec.decode(badName))
        val twice = HistoryRecordCodec.encode(gifWith(Reaction("iris", heart), Reaction("iris", thumbsUp)))
        assertNull(HistoryRecordCodec.decode(twice), "one reaction per member")
    }

    @Test
    fun onlyAGifHasReactions() {
        val rename =
            StoredMessage(ChatMessage(group, ref, "bob", 4L, MessageBody.GroupMeta("n"), null, 1L, listOf(Reaction("iris", heart))), 1L)
        assertFailsWith<IllegalStateException> { HistoryRecordCodec.encode(rename) }
    }

    @Test
    fun theWorstGifWithTheMostReactionsStillFitsTheCipherLimit() {
        // The longest slug and URL the rules allow (128 characters, 64 in the file name), a caption of 120 four-byte symbols,
        // the longest names and group id, and the 32 longest emojis of the table.
        val longUrl = "https://static2.klipy.com/ii/0123456789abcdef0123456789abcdef/ab/cd/" + "a".repeat(64) + ".webp"
        val widest = assertNotNull(GifReference.of("klipy", "s".repeat(128), longUrl, 2048, 2048))
        val longCaption = "\uD83D\uDE00".repeat(ContentCodec.CAPTION_MAX_CODE_POINTS)
        val longest = ReactionEmojis.all.sortedByDescending { it.encodeToByteArray().size }.take(ReactionRules.MAX_PER_MESSAGE)
        val reactions = List(ReactionRules.MAX_PER_MESSAGE) { Reaction("u".repeat(32 - 2) + "%02d".format(it), longest[it]) }
        val bytes =
            HistoryRecordCodec.encode(
                StoredMessage(
                    ChatMessage(
                        KeyBytes(ByteArray(128)),
                        ref,
                        "u".repeat(32),
                        4L,
                        MessageBody.Gif(widest, longCaption),
                        null,
                        123L,
                        reactions,
                    ),
                    9L,
                ),
            )
        assertTrue(bytes.size <= MAX_HISTORY_PLAINTEXT, "record is ${bytes.size} bytes")
    }

    private companion object {
        /** `MAX_PLAINTEXT` of `crypto/src/history.rs`: 4 buckets of 1024 minus the 4-byte header. */
        const val MAX_HISTORY_PLAINTEXT = 4 * 1024 - 4
    }
}
