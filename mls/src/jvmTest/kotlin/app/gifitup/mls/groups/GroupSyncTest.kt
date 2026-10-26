package app.gifitup.mls.groups

import app.gifitup.shared.data.contacts.KeyBytes
import app.gifitup.shared.data.network.ApiError
import app.gifitup.shared.domain.groups.ContactPins
import app.gifitup.shared.domain.groups.GroupStatus
import app.gifitup.shared.domain.groups.PinCheck
import app.gifitup.shared.domain.groups.RejectReason
import app.gifitup.shared.domain.groups.SyncOutcome
import app.gifitup.shared.domain.groups.WelcomeOutcome
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The receive side of the group lifecycle (ADR-0016) with real MLS clients and an in-memory Delivery Service. */
class GroupSyncTest {
    private val ds = InMemoryDelivery()

    private fun party(name: String) = Party(name, ds)

    /** Alice has created a group with Bob; Bob has joined it. */
    private suspend fun joinedPair(): Triple<Party, Party, ByteArray> {
        val alice = party("alice")
        val bob = party("bob")
        val groupId = alice.createGroupWith(bob)
        assertIs<WelcomeOutcome.Joined>(
            bob.engine
                .processWelcomes()
                .results
                .single(),
        )
        return Triple(alice, bob, groupId)
    }

    @Test
    fun aWelcomeJoinsTheGroupAtItsJoinPointAndIsAcknowledged() =
        runTest {
            val (_, bob, groupId) = joinedPair()
            assertEquals(GroupStatus.ACTIVE, bob.engine.recordOf(groupId)?.status)
            assertEquals(ds.ackedSeq(groupId, bob.deviceId), bob.engine.recordOf(groupId)?.cursor)
            assertEquals(2, bob.mls.memberCount(groupId))
            assertTrue(
                bob.engine
                    .processWelcomes()
                    .results
                    .isEmpty(),
                "the welcome was acknowledged",
            )
            assertTrue(bob.saves >= 1)
        }

    @Test
    fun messagesArriveInOrderWithTheirAuthenticatedSenderAndAreAcknowledgedAfterBeingStored() =
        runTest {
            val (alice, bob, groupId) = joinedPair()
            val first = alice.say(groupId, "one")
            val second = alice.say(groupId, "two")
            assertEquals(SyncOutcome.CaughtUp(2), bob.engine.sync(groupId))
            assertEquals(listOf("one", "two"), bob.received.map { it.plaintext.decodeToString() })
            assertEquals(listOf(first, second), bob.received.map { it.seq })
            assertEquals(setOf("alice"), bob.received.map { it.senderUsername }.toSet())
            assertEquals(second, ds.ackedSeq(groupId, bob.deviceId))
            // The stored snapshot already holds the cursor: a restart does not fetch or deliver anything again.
            bob.restart()
            assertEquals(second, bob.engine.recordOf(groupId)?.cursor)
            assertEquals(SyncOutcome.CaughtUp(0), bob.engine.sync(groupId))
            assertEquals(2, bob.received.size)
        }

    @Test
    fun aCrashBetweenStoringAndAcknowledgingLosesNothingAndDeliversNothingTwice() =
        runTest {
            val (alice, bob, groupId) = joinedPair()
            alice.say(groupId, "one")
            ds.failing = setOf("ack")
            assertEquals(SyncOutcome.Unavailable(ApiError.NETWORK), bob.engine.sync(groupId))
            ds.failing = emptySet()
            bob.restart()
            alice.say(groupId, "two")
            assertEquals(SyncOutcome.CaughtUp(1), bob.engine.sync(groupId))
            assertEquals(listOf("one", "two"), bob.received.map { it.plaintext.decodeToString() })
        }

    @Test
    fun aFailedStoreStopsBeforeAnythingIsAcknowledged() =
        runTest {
            val (alice, bob, groupId) = joinedPair()
            val seq = alice.say(groupId, "one")
            bob.failSave = true
            assertEquals(SyncOutcome.StorageFailed, bob.engine.sync(groupId))
            assertTrue(ds.ackedSeq(groupId, bob.deviceId) < seq)
            bob.failSave = false
            bob.restart()
            assertEquals(SyncOutcome.CaughtUp(1), bob.engine.sync(groupId))
        }

    @Test
    fun aCommitIsAppliedAndTheNewMemberCanTalk() =
        runTest {
            val (alice, bob, groupId) = joinedPair()
            val carol = party("carol")
            alice.add(groupId, carol)
            assertEquals(SyncOutcome.CaughtUp(1), bob.engine.sync(groupId))
            assertEquals(3, bob.mls.memberCount(groupId))
            assertIs<WelcomeOutcome.Joined>(
                carol.engine
                    .processWelcomes()
                    .results
                    .single(),
            )
            carol.say(groupId, "hi")
            assertEquals(SyncOutcome.CaughtUp(1), bob.engine.sync(groupId))
            assertEquals("carol", bob.received.last().senderUsername)
        }

    @Test
    fun aCommitThatDoesNotMatchItsEnvelopeFreezesTheGroupAndIsNotMerged() =
        runTest {
            val (alice, bob, groupId) = joinedPair()
            val carol = party("carol")
            alice.addWithEnvelope(groupId, carol, extraDevice = "dev-ghost")
            val epochBefore = bob.mls.epoch(groupId)
            assertEquals(SyncOutcome.Stopped(GroupStatus.FROZEN_ENVELOPE_MISMATCH), bob.engine.sync(groupId))
            assertEquals(epochBefore, bob.mls.epoch(groupId))
            // The freeze is stored: it survives a restart, and nothing more is fetched.
            bob.restart()
            assertEquals(SyncOutcome.Stopped(GroupStatus.FROZEN_ENVELOPE_MISMATCH), bob.engine.sync(groupId))
        }

    @Test
    fun aCommitAddingAKeyThatIsNotTheContactsPinFreezesTheGroup() =
        runTest {
            val (alice, bob, groupId) = joinedPair()
            bob.pins = ContactPins { name, _ -> if (name == "carol") PinCheck.MISMATCH else PinCheck.STRANGER }
            alice.add(groupId, party("carol"))
            assertEquals(SyncOutcome.Stopped(GroupStatus.FROZEN_CONTACT_MISMATCH), bob.engine.sync(groupId))
            assertEquals(2, bob.mls.memberCount(groupId))
        }

    @Test
    fun anUnavailableContactBookStagesNothingAndFreezesNothing() =
        runTest {
            val (alice, bob, groupId) = joinedPair()
            alice.add(groupId, party("carol"))
            bob.pins = null
            assertEquals(SyncOutcome.LookupIncomplete, bob.engine.sync(groupId))
            assertTrue(bob.mls.exportState().isNotEmpty(), "nothing is staged")
            bob.pins = ContactPins { _, _ -> PinCheck.STRANGER }
            assertEquals(SyncOutcome.CaughtUp(1), bob.engine.sync(groupId))
        }

    @Test
    fun aSecondLeafWithAKnownNameIsFlaggedNewAndUnverified() =
        runTest {
            val (alice, bob, groupId) = joinedPair()
            val otherBob = Party("bob", ds, deviceSuffix = "-2")
            alice.add(groupId, otherBob)
            assertEquals(SyncOutcome.CaughtUp(1), bob.engine.sync(groupId))
            val flagged =
                bob.engine
                    .recordOf(groupId)
                    ?.unverified
                    .orEmpty()
            assertEquals(listOf(KeyBytes(otherBob.mls.publicIdentity().signaturePublicKey)), flagged)
        }

    @Test
    fun aFlagDisappearsWhenItsLeafIsRemoved() =
        runTest {
            val (alice, bob, groupId) = joinedPair()
            val otherBob = Party("bob", ds, deviceSuffix = "-2")
            alice.add(groupId, otherBob)
            bob.engine.sync(groupId)
            assertEquals(
                1,
                bob.engine
                    .recordOf(groupId)
                    ?.unverified
                    ?.size,
            )
            alice.remove(groupId, otherBob)
            assertEquals(SyncOutcome.CaughtUp(1), bob.engine.sync(groupId))
            assertEquals(emptyList(), bob.engine.recordOf(groupId)?.unverified)
        }

    /** CR-5: only the message in front of the cursor can have killed the client. */
    @Test
    fun aFailureIsRecordedOnlyForTheNextMessage() =
        runTest {
            val (alice, bob, groupId) = joinedPair()
            val seq = alice.say(groupId, "one")
            bob.engine.recordFailure(groupId, seq + 5)
            assertEquals(emptyMap(), bob.engine.recordOf(groupId)?.failures)
            bob.engine.recordFailure(groupId, seq)
            assertEquals(mapOf(seq to 1), bob.engine.recordOf(groupId)?.failures)
        }

    @Test
    fun aJumpInTheLogIsAGapAndFreezesTheGroup() =
        runTest {
            val (alice, bob, groupId) = joinedPair()
            alice.say(groupId, "one")
            alice.say(groupId, "two")
            ds.trim(groupId, ds.lastSeq(groupId) - 1)
            assertEquals(SyncOutcome.Stopped(GroupStatus.FROZEN_GAP), bob.engine.sync(groupId))
            assertTrue(bob.received.isEmpty())
        }

    /**
     * A damaged message fails once; its generation is then used up, so the second try is a replay and the message is
     * skipped for good while the ones behind it flow.
     */
    @Test
    fun aDamagedMessageFailsOnceThenIsSkippedAndTheRestFlows() =
        runTest {
            val (alice, bob, groupId) = joinedPair()
            val bad = alice.say(groupId, "bad")
            alice.say(groupId, "good")
            ds.tamper(groupId, bad) { it.copyOf().also { b -> b[b.size - 1] = (b[b.size - 1].toInt() xor 1).toByte() } }
            assertEquals(SyncOutcome.Retry(bad), bob.engine.sync(groupId))
            assertEquals(mapOf(bad to 1), bob.engine.recordOf(groupId)?.failures)
            assertEquals(SyncOutcome.CaughtUp(2), bob.engine.sync(groupId))
            assertEquals(listOf("good"), bob.received.map { it.plaintext.decodeToString() })
            assertEquals(listOf(bad), bob.engine.recordOf(groupId)?.quarantined)
        }

    /** Three failed attempts quarantine a message even when each fails differently (counted across restarts). */
    @Test
    fun threeRecordedFailuresQuarantineAMessage() =
        runTest {
            val (alice, bob, groupId) = joinedPair()
            val seq = alice.say(groupId, "one")
            repeat(2) { assertEquals(SyncOutcome.Retry(seq), bob.engine.recordFailure(groupId, seq)) }
            bob.restart()
            assertEquals(mapOf(seq to 2), bob.engine.recordOf(groupId)?.failures)
            bob.engine.recordFailure(groupId, seq)
            assertEquals(listOf(seq), bob.engine.recordOf(groupId)?.quarantined)
            assertEquals(seq, bob.engine.recordOf(groupId)?.cursor)
            assertEquals(SyncOutcome.CaughtUp(0), bob.engine.sync(groupId))
            assertTrue(bob.received.isEmpty())
        }

    @Test
    fun aCiphertextDeliveredTwiceIsSkippedAtOnce() =
        runTest {
            val (alice, bob, groupId) = joinedPair()
            val seq = alice.say(groupId, "once")
            ds.duplicate(groupId, seq)
            alice.say(groupId, "next")
            assertEquals(SyncOutcome.CaughtUp(3), bob.engine.sync(groupId))
            assertEquals(listOf("once", "next"), bob.received.map { it.plaintext.decodeToString() })
        }

    @Test
    fun ourOwnMessagesMoveTheCursorWithoutBeingDecrypted() =
        runTest {
            val (_, bob, groupId) = joinedPair()
            val seq = bob.say(groupId, "mine")
            assertEquals(SyncOutcome.CaughtUp(1), bob.engine.sync(groupId))
            assertEquals(seq, bob.engine.recordOf(groupId)?.cursor)
            assertTrue(bob.received.isEmpty())
        }

    /** SEC-1: the sender label is the server's word; a commit labelled as ours must not be skipped. */
    @Test
    fun aCommitLabelledAsOursIsStillApplied() =
        runTest {
            val (alice, bob, groupId) = joinedPair()
            alice.add(groupId, party("carol"))
            ds.relabel(groupId, ds.lastSeq(groupId), bob.deviceId)
            assertEquals(SyncOutcome.CaughtUp(1), bob.engine.sync(groupId))
            assertEquals(3, bob.mls.memberCount(groupId))
        }

    /** SEC-2: the 50th group is the last; a welcome for another is dropped, and forgetting a group frees its slot. */
    @Test
    fun theGroupCapDropsFurtherWelcomesAndForgettingFreesASlot() =
        runTest {
            val bob = party("bob")
            val groups = (1..50).map { party("host$it").createGroupWith(bob) }
            assertEquals(
                50,
                bob.engine
                    .processWelcomes()
                    .results
                    .count { it is WelcomeOutcome.Joined },
            )
            val extra = party("extra").createGroupWith(bob)
            val report = bob.engine.processWelcomes()
            assertEquals(WelcomeOutcome.Rejected(RejectReason.GROUP_LIMIT), report.results.single())
            assertEquals(null, bob.engine.recordOf(extra))
            assertEquals(SyncOutcome.CaughtUp(0), bob.engine.forget(groups.first()))
            assertEquals(null, bob.engine.recordOf(groups.first()))
            bob.restart()
            assertEquals(49, bob.engine.statuses().size)
        }

    @Test
    fun aGroupTheServerNoLongerShowsIsGoneAndItsStateDeleted() =
        runTest {
            val (_, bob, groupId) = joinedPair()
            ds.viewFor(bob.deviceId).leave(groupId)
            assertEquals(SyncOutcome.Stopped(GroupStatus.REMOVED), bob.engine.sync(groupId))
            assertEquals(GroupStatus.REMOVED, bob.engine.recordOf(groupId)?.status)
            assertEquals(0, bob.mls.memberCountOrZero(groupId))
            bob.restart()
            assertEquals(SyncOutcome.Stopped(GroupStatus.REMOVED), bob.engine.sync(groupId))
        }

    @Test
    fun aDeadClientHaltsWithoutStoringAnything() =
        runTest {
            val (alice, bob, groupId) = joinedPair()
            val seq = alice.say(groupId, "one")
            val savesBefore = bob.saves
            val dying = PoisonedOnStage(bob.mls)
            val engine = bob.newEngine(dying)
            assertEquals(SyncOutcome.Poisoned(seq), engine.sync(groupId))
            assertEquals(savesBefore, bob.saves)
            bob.restart()
            assertEquals(SyncOutcome.Retry(seq), bob.engine.recordFailure(groupId, seq))
            assertEquals(mapOf(seq to 1), bob.engine.recordOf(groupId)?.failures)
        }

    @Test
    fun anUnusableWelcomeIsDroppedAndAWelcomeForAMismatchedContactIsRejected() =
        runTest {
            val alice = party("alice")
            val bob = party("bob")
            val groupId = alice.createGroupWith(bob)
            bob.pins = ContactPins { name, _ -> if (name == "alice") PinCheck.MISMATCH else PinCheck.STRANGER }
            val report = bob.engine.processWelcomes()
            assertEquals(WelcomeOutcome.Rejected(RejectReason.CONTACT_MISMATCH), report.results.single())
            assertTrue(
                bob.engine
                    .processWelcomes()
                    .results
                    .isEmpty(),
                "dropped for good",
            )
            assertEquals(null, bob.engine.recordOf(groupId))
        }
}

private fun app.gifitup.shared.data.crypto.MlsClient.memberCountOrZero(groupId: ByteArray): Int =
    try {
        memberCount(groupId)
    } catch (e: app.gifitup.shared.data.crypto.MlsException) {
        check(e.code == app.gifitup.shared.data.crypto.MlsErrorCode.UnknownGroup)
        0
    }
