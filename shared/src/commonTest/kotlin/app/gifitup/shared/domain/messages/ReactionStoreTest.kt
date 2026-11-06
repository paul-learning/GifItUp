package app.gifitup.shared.domain.messages

import app.gifitup.shared.data.content.GifReference
import app.gifitup.shared.data.content.ReactionEmojis
import app.gifitup.shared.data.crypto.FakeHistoryCipher
import app.gifitup.shared.data.storage.InMemoryRecordStore
import app.gifitup.shared.domain.contacts.KeyBytes
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** What a store does with reactions (ADR-0006 B10a, [ReactionRules]), for both stores; the durable one has more to keep. */
class ReactionStoreTest {
    private val url = "https://static.klipy.com/ii/0123456789abcdef0123456789abcdef/ab/cd/funny_cat-1.webp"
    private val gif = assertNotNull(GifReference.of("klipy", "funny-cat", url, 320, 240))
    private val records = InMemoryRecordStore()
    private val heart = "❤️"
    private val thumbsUp = "👍"
    private val group = KeyBytes(ByteArray(16) { 1 })

    private fun ref(n: Int) = KeyBytes(ByteArray(32) { n.toByte() })

    private fun encrypted(limits: HistoryLimits = HistoryLimits()) = EncryptedMessageStore(FakeHistoryCipher(), records, limits)

    private fun received(n: Int) = ChatMessage(group, ref(n), "bob", null, MessageBody.Gif(gif, null), null, 100L + n)

    private fun stores(): List<MessageStore> = listOf(InMemoryMessageStore(), encrypted())

    private fun List<Reaction>.pairs() = map { it.by to it.emoji }

    @Test
    fun aReactionIsRecordedTheLatestWinsAndItCanBeTakenBack() =
        runTest {
            for (s in stores()) {
                s.insert(received(1))
                assertTrue(s.react(group, ref(1), "iris", heart))
                assertTrue(s.react(group, ref(1), "cy_2", thumbsUp))
                assertEquals(
                    listOf("iris" to heart, "cy_2" to thumbsUp),
                    s
                        .messages(group)
                        .single()
                        .reactions
                        .pairs(),
                )
                assertTrue(s.react(group, ref(1), "iris", thumbsUp), "a changed reaction")
                assertFalse(s.react(group, ref(1), "iris", thumbsUp), "the same again changes nothing")
                assertEquals(
                    listOf("iris" to thumbsUp, "cy_2" to thumbsUp),
                    s
                        .messages(group)
                        .single()
                        .reactions
                        .pairs(),
                    "keeps its place",
                )
                assertTrue(s.react(group, ref(1), "iris", null))
                assertFalse(s.react(group, ref(1), "iris", null), "nothing to take back")
                assertEquals(
                    listOf("cy_2" to thumbsUp),
                    s
                        .messages(group)
                        .single()
                        .reactions
                        .pairs(),
                )
            }
        }

    @Test
    fun aReactionThatBreaksARuleChangesNothing() =
        runTest {
            for (s in stores()) {
                s.insert(received(1))
                s.insert(ChatMessage(group, ref(2), "bob", null, MessageBody.GroupMeta("Trip"), null, 1L))
                assertFalse(s.react(group, ref(9), "iris", heart), "unknown target")
                assertFalse(s.react(KeyBytes(ByteArray(16) { 7 }), ref(1), "iris", heart), "another group")
                assertFalse(s.react(group, ref(2), "iris", heart), "not a gif")
                assertFalse(s.react(group, ref(1), "iris", "x"), "not in the table")
                assertFalse(s.react(group, ref(1), "iris", "❤"), "the bare heart is not the table's entry")
                assertFalse(s.react(group, ref(1), "Iris!", heart), "not a username")
                assertEquals(emptyList(), s.messages(group).flatMap { it.reactions })
            }
        }

    @Test
    fun aMessageTakesAtMostThirtyTwoMembersButTheirOwnCanStillChange() =
        runTest {
            for (s in stores()) {
                s.insert(received(1))
                repeat(ReactionRules.MAX_PER_MESSAGE) { assertTrue(s.react(group, ref(1), "user_%02d".format(it), heart), "member $it") }
                assertFalse(s.react(group, ref(1), "one_too_many", heart))
                assertTrue(s.react(group, ref(1), "user_00", thumbsUp))
                assertTrue(s.react(group, ref(1), "user_01", null))
                assertTrue(s.react(group, ref(1), "newcomer", heart), "room again")
                assertEquals(
                    ReactionRules.MAX_PER_MESSAGE,
                    s
                        .messages(group)
                        .single()
                        .reactions.size,
                )
            }
        }

    @Test
    fun aStateChangeKeepsTheReactions() =
        runTest {
            for (s in stores()) {
                s.insert(ChatMessage(group, ref(1), null, null, MessageBody.Gif(gif, null), DeliveryState.SENDING, 5L))
                assertTrue(s.react(group, ref(1), "iris", heart))
                s.setState(group, ref(1), DeliveryState.SENT, 3L)
                val message = s.messages(group).single()
                assertEquals(DeliveryState.SENT, message.state)
                assertEquals(listOf("iris" to heart), message.reactions.pairs())
            }
        }

    @Test
    fun theReactionsSurviveANewStoreAndGoWhenTheGroupIsForgotten() =
        runTest {
            val first = encrypted()
            first.insert(received(1))
            first.react(group, ref(1), "iris", heart)
            assertEquals(
                listOf("iris" to heart),
                encrypted()
                    .messages(group)
                    .single()
                    .reactions
                    .pairs(),
            )
            first.forget(group)
            assertEquals(emptyList(), encrypted().messages(group))
            assertEquals(emptyList(), records.folders().flatMap { records.list(it) }, "no record of the group is left")
        }

    @Test
    fun theReactionsLeaveWithTheirMessageWhenRetentionRemovesIt() =
        runTest {
            val s = encrypted(HistoryLimits(maxPerGroup = 2))
            s.insert(received(1))
            s.react(group, ref(1), "iris", heart)
            s.insert(received(2))
            s.insert(received(3))
            assertEquals(listOf(ref(2), ref(3)), s.messages(group).map { it.reference })
            assertFalse(s.react(group, ref(1), "iris", thumbsUp), "the message is gone, so is its reaction")
            assertEquals(2, records.folders().flatMap { records.list(it) }.size, "one record per message and nothing else")
        }

    @Test
    fun aFailedWriteLeavesTheOldReactions() =
        runTest {
            val s = encrypted()
            s.insert(received(1))
            s.react(group, ref(1), "iris", heart)
            records.failNextWrite = true
            val failure = assertFailsWith<HistoryStoreException> { s.react(group, ref(1), "cy_2", thumbsUp) }
            assertEquals(HistoryStoreException.Kind.STORAGE, failure.kind)
            assertEquals(
                listOf("iris" to heart),
                s
                    .messages(group)
                    .single()
                    .reactions
                    .pairs(),
            )
            assertEquals(
                listOf("iris" to heart),
                encrypted()
                    .messages(group)
                    .single()
                    .reactions
                    .pairs(),
            )
            assertTrue(s.react(group, ref(1), "cy_2", thumbsUp), "the retry works")
        }

    @Test
    fun aRecordTooBigToSealIsNeverTurnedIntoUnavailableByAReaction() =
        runTest {
            val small = FakeHistoryCipher(maxPlaintext = 330)
            val s = EncryptedMessageStore(small, records, HistoryLimits())
            s.insert(received(1))
            s.react(group, ref(1), "iris", heart)
            val failure =
                runCatching {
                    repeat(
                        ReactionRules.MAX_PER_MESSAGE,
                    ) { s.react(group, ref(1), "user_%02d".format(it), ReactionEmojis.all[it]) }
                }
            assertTrue(failure.isFailure, "the cipher refused a record over its limit")
            assertTrue(s.messages(group).single().body is MessageBody.Gif, "the GIF stays a GIF")
            assertTrue(EncryptedMessageStore(small, records, HistoryLimits()).messages(group).single().body is MessageBody.Gif)
        }
}
