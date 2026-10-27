package app.gifitup.mls.groups

import app.gifitup.mls.UniffiMlsClientFactory
import app.gifitup.shared.data.network.ApiError
import app.gifitup.shared.domain.groups.GroupStatus
import app.gifitup.shared.domain.groups.SyncOutcome
import app.gifitup.shared.domain.groups.WelcomeOutcome
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The send side (ADR-0016): persist before post, safe repeats, own seqs, and the freshness check. */
class GroupSendTest {
    private val ds = InMemoryDelivery()

    private fun party(name: String) = Party(name, ds)

    /** A third party creates the group; Alice and Bob join through their engines. */
    private suspend fun joinedPair(): Triple<Party, Party, ByteArray> {
        val alice = party("alice")
        val bob = party("bob")
        val groupId = party("carol").createGroupWith(alice, bob)
        assertIs<WelcomeOutcome.Joined>(
            alice.engine
                .processWelcomes()
                .results
                .single(),
        )
        assertIs<WelcomeOutcome.Joined>(
            bob.engine
                .processWelcomes()
                .results
                .single(),
        )
        return Triple(alice, bob, groupId)
    }

    private fun texts(party: Party) = party.received.map { it.plaintext.decodeToString() }

    @Test
    fun aSentMessageReachesTheOthersInOrderAndOurOwnCopyIsSkipped() =
        runTest {
            val (alice, bob, groupId) = joinedPair()
            val first = assertIs<SyncOutcome.Sent>(alice.engine.send(groupId, "one".encodeToByteArray()))
            val second = assertIs<SyncOutcome.Sent>(alice.engine.send(groupId, "two".encodeToByteArray()))
            assertEquals(SyncOutcome.CaughtUp(2), bob.engine.sync(groupId))
            assertEquals(listOf("one", "two"), texts(bob))
            assertEquals(SyncOutcome.CaughtUp(2), alice.engine.sync(groupId))
            assertTrue(alice.received.isEmpty(), "our own messages are not delivered back")
            assertEquals(second.seq, alice.engine.recordOf(groupId)?.cursor)
            assertEquals(emptyMap(), alice.engine.recordOf(groupId)?.failures)
            assertEquals(emptyList(), alice.engine.recordOf(groupId)?.ownSeqs)
            assertTrue(first.seq < second.seq)
        }

    @Test
    fun theSenderLearnsTheMessageReferenceBeforeThePostAndTheRecipientComputesTheSame() =
        runTest {
            val (alice, bob, groupId) = joinedPair()
            var announced: ByteArray? = null
            var postedAlready: Boolean? = null
            ds.onPost = { device, _ -> if (device == alice.deviceId) postedAlready = announced == null }
            assertIs<SyncOutcome.Sent>(alice.engine.send(groupId, "one".encodeToByteArray()) { announced = it })
            assertEquals(false, postedAlready, "the reference was announced before the post")
            bob.engine.sync(groupId)
            assertContentEquals(announced, bob.received.single().messageRef)
        }

    @Test
    fun anObserverThatFailsDoesNotStopTheSend() =
        runTest {
            val (alice, _, groupId) = joinedPair()
            assertIs<SyncOutcome.Sent>(alice.engine.send(groupId, "one".encodeToByteArray()) { error("history is broken") })
        }

    @Test
    fun theMessageIsStoredBeforeItIsPosted() =
        runTest {
            val (alice, _, groupId) = joinedPair()
            var seenPending: Boolean? = null
            ds.onPost = { device, group ->
                if (device == alice.deviceId) {
                    val restored = UniffiMlsClientFactory.restore(alice.saved.copyOf(), ByteArray(32) { 7 })
                    seenPending = alice.newEngine(restored).recordOf(group)?.pending != null
                    restored.close()
                }
            }
            assertIs<SyncOutcome.Sent>(alice.engine.send(groupId, "hello".encodeToByteArray()))
            assertEquals(true, seenPending, "the stored snapshot already held the pending message when it was posted")
            assertNull(alice.engine.recordOf(groupId)?.pending)
        }

    @Test
    fun aLostAnswerLeavesItPendingAndResumingPostsTheSameBytesOnce() =
        runTest {
            val (alice, bob, groupId) = joinedPair()
            ds.lostAnswers = setOf("post")
            assertEquals(SyncOutcome.InFlight(ApiError.NETWORK), alice.engine.send(groupId, "once".encodeToByteArray()))
            assertNotNull(alice.engine.recordOf(groupId)?.pending)
            ds.lostAnswers = emptySet()
            alice.restart()
            val resumed = assertIs<SyncOutcome.Sent>(alice.engine.resume(groupId))
            assertNull(alice.engine.recordOf(groupId)?.pending)
            assertEquals(SyncOutcome.CaughtUp(1), bob.engine.sync(groupId))
            assertEquals(listOf("once"), texts(bob))
            assertEquals(resumed.seq, ds.lastSeq(groupId))
        }

    @Test
    fun aFailedPostLeavesItPendingAndANewSendSettlesItFirst() =
        runTest {
            val (alice, bob, groupId) = joinedPair()
            ds.failing = setOf("post")
            assertEquals(SyncOutcome.InFlight(ApiError.NETWORK), alice.engine.send(groupId, "first".encodeToByteArray()))
            ds.failing = emptySet()
            assertIs<SyncOutcome.Sent>(alice.engine.send(groupId, "second".encodeToByteArray()))
            assertEquals(SyncOutcome.CaughtUp(2), bob.engine.sync(groupId))
            assertEquals(listOf("first", "second"), texts(bob))
        }

    @Test
    fun anOlderMessageThatDoesNotGoThroughKeepsTheNewTextWaiting() =
        runTest {
            val (alice, bob, groupId) = joinedPair()
            ds.failing = setOf("post")
            assertEquals(SyncOutcome.InFlight(ApiError.NETWORK), alice.engine.send(groupId, "first".encodeToByteArray()))
            assertEquals(
                SyncOutcome.PendingFirst(SyncOutcome.InFlight(ApiError.NETWORK)),
                alice.engine.send(groupId, "second".encodeToByteArray()),
            )
            ds.failing = emptySet()
            assertIs<SyncOutcome.Sent>(alice.engine.resume(groupId))
            bob.engine.sync(groupId)
            assertEquals(listOf("first"), texts(bob), "the second text was never sealed")
        }

    @Test
    fun aRefusedMessageIsDroppedForGoodAndTheNextOneWorks() =
        runTest {
            val (alice, bob, groupId) = joinedPair()
            ds.refusePosts = ApiError.ENVELOPE_INVALID
            assertEquals(SyncOutcome.Rejected(ApiError.ENVELOPE_INVALID), alice.engine.send(groupId, "no".encodeToByteArray()))
            assertNull(alice.engine.recordOf(groupId)?.pending)
            ds.refusePosts = null
            assertIs<SyncOutcome.Sent>(alice.engine.send(groupId, "yes".encodeToByteArray()))
            bob.engine.sync(groupId)
            assertEquals(listOf("yes"), texts(bob))
        }

    @Test
    fun aStateOlderThanAnAcceptedSendBecomesReadOnlyAndSendsNothing() =
        runTest {
            val (alice, bob, groupId) = joinedPair()
            alice.engine.send(groupId, "m1".encodeToByteArray())
            val older = alice.saved.copyOf()
            alice.engine.send(groupId, "m2".encodeToByteArray())
            val lastSeq = ds.lastSeq(groupId)
            alice.saved = older
            alice.restart()
            assertEquals(SyncOutcome.Stopped(GroupStatus.READ_ONLY_ROLLBACK), alice.engine.send(groupId, "m3".encodeToByteArray()))
            assertEquals(lastSeq, ds.lastSeq(groupId), "nothing was posted")
            alice.restart()
            assertEquals(GroupStatus.READ_ONLY_ROLLBACK, alice.engine.recordOf(groupId)?.status)
            assertEquals(SyncOutcome.Stopped(GroupStatus.READ_ONLY_ROLLBACK), alice.engine.send(groupId, "m4".encodeToByteArray()))
            bob.engine.sync(groupId)
            assertEquals(listOf("m1", "m2"), texts(bob))
        }

    /** SEC-1: freshness is checked before an old pending message is posted again, also when the log forgot it. */
    @Test
    fun anOldPendingMessageIsNotPostedAgainIntoALogThatForgotIt() =
        runTest {
            val (alice, _, groupId) = joinedPair()
            ds.lostAnswers = setOf("post")
            alice.engine.send(groupId, "p".encodeToByteArray())
            ds.lostAnswers = emptySet()
            val oldState = alice.saved.copyOf()
            assertIs<SyncOutcome.Sent>(alice.engine.resume(groupId))
            assertIs<SyncOutcome.Sent>(alice.engine.send(groupId, "q".encodeToByteArray()))
            ds.trim(groupId, ds.lastSeq(groupId))
            val last = ds.lastSeq(groupId)
            alice.saved = oldState
            alice.restart()
            assertEquals(SyncOutcome.Stopped(GroupStatus.READ_ONLY_ROLLBACK), alice.engine.resume(groupId))
            assertEquals(last, ds.lastSeq(groupId), "nothing was posted into the emptied log")
        }

    /** SEC-2: the seq the server names for our post decides nothing unless the entry is our very message. */
    @Test
    fun aWrongSeqInTheAnswerDoesNotMakeUsSkipSomeoneElsesMessage() =
        runTest {
            val (alice, bob, groupId) = joinedPair()
            ds.misnameSeqBy = 1
            val named = assertIs<SyncOutcome.Sent>(bob.engine.send(groupId, "mine".encodeToByteArray())).seq
            ds.misnameSeqBy = 0
            alice.engine.sync(groupId)
            val real = alice.engine.send(groupId, "from alice".encodeToByteArray())
            assertEquals(named, (real as SyncOutcome.Sent).seq, "the server's named seq now holds Alice's message")
            repeat(3) { bob.engine.sync(groupId) }
            assertEquals(listOf("from alice"), texts(bob))
        }

    /** SEC-3: more than 100 sends without a sync would push own seqs out of the list; sending waits for a sync. */
    @Test
    fun sendingWaitsForASyncAfterAHundredOwnMessages() =
        runTest {
            val (alice, _, groupId) = joinedPair()
            repeat(100) { assertIs<SyncOutcome.Sent>(alice.engine.send(groupId, "m$it".encodeToByteArray())) }
            assertEquals(SyncOutcome.Busy, alice.engine.send(groupId, "one too many".encodeToByteArray()))
            alice.engine.sync(groupId)
            assertIs<SyncOutcome.Sent>(alice.engine.send(groupId, "after the sync".encodeToByteArray()))
        }

    @Test
    fun aTextLongerThanOneBlockIsRefusedBeforeAnythingIsSealed() =
        runTest {
            val (alice, _, groupId) = joinedPair()
            assertEquals(SyncOutcome.TooLong, alice.engine.send(groupId, ByteArray(897)))
            assertEquals(SyncOutcome.NotJoined, alice.engine.send(randomBytes(16), "x".encodeToByteArray()))
            assertNull(alice.engine.recordOf(groupId)?.pending)
        }

    @Test
    fun aGroupTheServerNoLongerShowsEndsASend() =
        runTest {
            val (alice, _, groupId) = joinedPair()
            ds.viewFor(alice.deviceId).leave(groupId)
            assertEquals(SyncOutcome.Stopped(GroupStatus.REMOVED), alice.engine.send(groupId, "x".encodeToByteArray()))
        }
}
