package app.gifitup.mls.groups

import app.gifitup.shared.data.network.ApiError
import app.gifitup.shared.domain.groups.GroupStatus
import app.gifitup.shared.domain.groups.NewMember
import app.gifitup.shared.domain.groups.RemoveTarget
import app.gifitup.shared.domain.groups.SyncOutcome
import app.gifitup.shared.domain.groups.WelcomeOutcome
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Creating groups and adding or removing members through the engine (ADR-0016), with real MLS clients. */
class GroupCommitTest {
    private val ds = InMemoryDelivery()

    private fun party(name: String) = Party(name, ds)

    private fun member(p: Party) = NewMember(p.deviceId, p.keyPackage())

    private fun target(p: Party) = RemoveTarget(p.deviceId, p.mls.publicIdentity().signaturePublicKey)

    private fun texts(p: Party) = p.received.map { it.plaintext.decodeToString() }

    private suspend fun join(vararg parties: Party) =
        parties.forEach {
            assertIs<WelcomeOutcome.Joined>(
                it.engine
                    .processWelcomes()
                    .results
                    .single(),
            )
        }

    /** Alice creates a group with Bob; Bob has joined. */
    private suspend fun aliceAndBob(): Triple<Party, Party, ByteArray> {
        val alice = party("alice")
        val bob = party("bob")
        val created = alice.engine.createGroup(listOf(member(bob)))
        assertIs<SyncOutcome.Sent>(created.outcome)
        join(bob)
        return Triple(alice, bob, assertNotNull(created.groupId))
    }

    @Test
    fun aGroupIsCreatedAndBothSidesCanTalk() =
        runTest {
            val (alice, bob, groupId) = aliceAndBob()
            assertEquals(GroupStatus.ACTIVE, alice.engine.recordOf(groupId)?.status)
            assertIs<SyncOutcome.Sent>(alice.engine.send(groupId, "hi bob".encodeToByteArray()))
            assertIs<SyncOutcome.Sent>(bob.engine.send(groupId, "hi alice".encodeToByteArray()))
            assertEquals(SyncOutcome.CaughtUp(3), alice.engine.sync(groupId), "her own commit and message are skipped, Bob's is read")
            assertEquals(listOf("hi alice"), texts(alice))
            bob.engine.sync(groupId)
            assertEquals(listOf("hi bob"), texts(bob))
        }

    @Test
    fun aMemberIsAddedAndEveryoneCanTalk() =
        runTest {
            val (alice, bob, groupId) = aliceAndBob()
            val carol = party("carol")
            assertIs<SyncOutcome.Sent>(alice.engine.addMembers(groupId, listOf(member(carol))))
            join(carol)
            assertEquals(SyncOutcome.CaughtUp(1), bob.engine.sync(groupId))
            assertEquals(3, bob.mls.memberCount(groupId))
            assertIs<SyncOutcome.Sent>(carol.engine.send(groupId, "hello all".encodeToByteArray()))
            alice.engine.sync(groupId)
            bob.engine.sync(groupId)
            assertEquals(listOf("hello all"), texts(alice))
            assertEquals(listOf("hello all"), texts(bob))
        }

    @Test
    fun aRemovedMemberLosesTheGroupAndTheOthersKeepTalking() =
        runTest {
            val (alice, bob, groupId) = aliceAndBob()
            val carol = party("carol")
            alice.engine.addMembers(groupId, listOf(member(carol)))
            join(carol)
            bob.engine.sync(groupId)
            assertIs<SyncOutcome.Sent>(alice.engine.removeMembers(groupId, listOf(target(carol))))
            assertEquals(SyncOutcome.CaughtUp(1), bob.engine.sync(groupId))
            assertEquals(2, bob.mls.memberCount(groupId))
            assertEquals(SyncOutcome.Stopped(GroupStatus.REMOVED), carol.engine.sync(groupId))
            assertIs<SyncOutcome.Sent>(bob.engine.send(groupId, "just us".encodeToByteArray()))
            alice.engine.sync(groupId)
            assertEquals(listOf("just us"), texts(alice))
        }

    @Test
    fun aCommitThatLosesTheRaceAppliesTheWinnerAndTriesAgain() =
        runTest {
            val (alice, bob, groupId) = aliceAndBob()
            val dave = party("dave")
            val eve = party("eve")
            var raced = false
            ds.onPost = { device, group ->
                if (device == alice.deviceId && !raced) {
                    raced = true
                    assertIs<SyncOutcome.Sent>(bob.engine.addMembers(group, listOf(member(eve))))
                }
            }
            assertIs<SyncOutcome.Sent>(alice.engine.addMembers(groupId, listOf(member(dave))))
            join(dave, eve)
            assertEquals(4, alice.mls.memberCount(groupId))
            assertNull(alice.engine.recordOf(groupId)?.pending)
            bob.engine.sync(groupId)
            assertEquals(4, bob.mls.memberCount(groupId))
        }

    @Test
    fun aCommitReadsWhatWasPostedAheadOfItBeforeItMergesAndEndsTheEpoch() =
        runTest {
            val (alice, bob, groupId) = aliceAndBob()
            val carol = party("carol")
            var injected = false
            ds.onPost = { device, _ ->
                if (device == alice.deviceId && !injected) {
                    injected = true
                    assertIs<SyncOutcome.Sent>(bob.engine.send(groupId, "before your commit".encodeToByteArray()))
                }
            }
            assertIs<SyncOutcome.Sent>(alice.engine.addMembers(groupId, listOf(member(carol))))
            assertEquals(listOf("before your commit"), texts(alice))
        }

    @Test
    fun aLostAnswerToACommitIsResolvedByPostingTheSameEnvelopeAgain() =
        runTest {
            val (alice, bob, groupId) = aliceAndBob()
            val carol = party("carol")
            ds.lostAnswers = setOf("post")
            assertEquals(SyncOutcome.InFlight(ApiError.NETWORK), alice.engine.addMembers(groupId, listOf(member(carol))))
            assertNotNull(alice.engine.recordOf(groupId)?.pending)
            assertEquals(SyncOutcome.CommitPending, alice.engine.send(groupId, "wait".encodeToByteArray()))
            ds.lostAnswers = emptySet()
            alice.restart()
            assertIs<SyncOutcome.Sent>(alice.engine.resume(groupId))
            join(carol)
            assertEquals(SyncOutcome.CaughtUp(1), bob.engine.sync(groupId))
            assertEquals(3, bob.mls.memberCount(groupId))
            assertEquals(3, alice.mls.memberCount(groupId))
            assertIs<SyncOutcome.Sent>(alice.engine.send(groupId, "after".encodeToByteArray()))
        }

    @Test
    fun aLostAnswerToTheCreationIsResolvedToo() =
        runTest {
            val alice = party("alice")
            val bob = party("bob")
            ds.lostAnswers = setOf("create")
            val created = alice.engine.createGroup(listOf(member(bob)))
            assertEquals(SyncOutcome.InFlight(ApiError.NETWORK), created.outcome)
            val groupId = assertNotNull(created.groupId)
            ds.lostAnswers = emptySet()
            alice.restart()
            assertIs<SyncOutcome.Sent>(alice.engine.resume(groupId))
            join(bob)
            assertIs<SyncOutcome.Sent>(alice.engine.send(groupId, "works".encodeToByteArray()))
            bob.engine.sync(groupId)
            assertEquals(listOf("works"), texts(bob))
        }

    @Test
    fun aRefusedCommitIsDroppedCompletelyAndTheGroupKeepsWorking() =
        runTest {
            val (alice, bob, groupId) = aliceAndBob()
            // Bob's device is a member already, so the server refuses a commit that adds it again.
            val again = NewMember(bob.deviceId, party("carol").keyPackage())
            assertEquals(SyncOutcome.Rejected(ApiError.ENVELOPE_INVALID), alice.engine.addMembers(groupId, listOf(again)))
            assertNull(alice.engine.recordOf(groupId)?.pending)
            assertTrue(!alice.mls.hasPendingCommit(groupId))
            assertIs<SyncOutcome.Sent>(alice.engine.send(groupId, "still fine".encodeToByteArray()))
        }

    @Test
    fun aRefusedCreationLeavesNoGroupBehind() =
        runTest {
            val alice = party("alice")
            // The creator cannot be in the list of added devices: the server refuses that.
            val created = alice.engine.createGroup(listOf(NewMember(alice.deviceId, party("bob").keyPackage())))
            assertEquals(SyncOutcome.Rejected(ApiError.ENVELOPE_INVALID), created.outcome)
            assertTrue(alice.engine.statuses().isEmpty())
            assertTrue(alice.mls.exportState().isNotEmpty())
        }

    @Test
    fun badRequestsAreRefusedBeforeAnythingIsStored() =
        runTest {
            val (alice, bob, groupId) = aliceAndBob()
            assertEquals(SyncOutcome.InvalidRequest, alice.engine.addMembers(groupId, emptyList()))
            assertEquals(SyncOutcome.InvalidRequest, alice.engine.addMembers(groupId, listOf(NewMember(bob.deviceId, ByteArray(10)))))
            assertEquals(SyncOutcome.InvalidRequest, alice.engine.removeMembers(groupId, listOf(RemoveTarget(bob.deviceId, ByteArray(32)))))
            assertEquals(SyncOutcome.InvalidRequest, alice.engine.removeMembers(groupId, listOf(target(alice))))
            assertEquals(SyncOutcome.InvalidRequest, alice.engine.createGroup(emptyList()).outcome)
            assertNull(alice.engine.recordOf(groupId)?.pending)
        }

    @Test
    fun aStaleStateCannotCommitEither() =
        runTest {
            val (alice, _, groupId) = aliceAndBob()
            alice.engine.send(groupId, "m1".encodeToByteArray())
            val older = alice.saved.copyOf()
            alice.engine.send(groupId, "m2".encodeToByteArray())
            alice.saved = older
            alice.restart()
            val last = ds.lastSeq(groupId)
            assertEquals(
                SyncOutcome.Stopped(GroupStatus.READ_ONLY_ROLLBACK),
                alice.engine.addMembers(groupId, listOf(member(party("carol")))),
            )
            assertEquals(last, ds.lastSeq(groupId))
        }
}
