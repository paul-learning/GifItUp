package app.gifitup.shared.domain.groups

import app.gifitup.shared.data.crypto.IncomingMessage
import app.gifitup.shared.data.crypto.LeafInfo
import app.gifitup.shared.data.network.GroupMessageEntry
import app.gifitup.shared.data.network.MessageKind
import app.gifitup.shared.domain.contacts.KeyBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class CommitJudgeTest {
    private fun leaf(
        name: String,
        key: Int,
        index: Int = 0,
    ) = LeafInfo(name, ByteArray(32) { key.toByte() }, index)

    private val alice = leaf("alice", 1, 0)
    private val bob = leaf("bob", 2, 1)

    private fun commit(
        added: List<LeafInfo> = emptyList(),
        removed: List<LeafInfo> = emptyList(),
        epoch: Long = 1,
    ) = IncomingMessage.Commit(1, alice, epoch, added, removed, false)

    private fun entry(
        added: Int = 0,
        removed: Int = 0,
        epoch: Long? = 1,
    ) = GroupMessageEntry(
        5,
        "dev-alice",
        MessageKind.COMMIT,
        epoch,
        List(added) { "dev-added-$it" },
        List(removed) { "dev-removed-$it" },
        ByteArray(1),
    )

    private val strangers = ContactPins { _, _ -> PinCheck.STRANGER }

    private fun judge(
        commit: IncomingMessage.Commit,
        entry: GroupMessageEntry,
        pins: ContactPins = strangers,
    ) = CommitJudge.judge(commit, entry, listOf(alice, bob), pins)

    @Test
    fun aCommitThatMatchesItsEnvelopeIsMerged() {
        val merge = assertIs<CommitDecision.Merge>(judge(commit(added = listOf(leaf("carol", 3))), entry(added = 1)))
        assertEquals(emptyList(), merge.unverified)
    }

    @Test
    fun differentCountsOrAnotherEpochFreezeAsAnEnvelopeMismatch() {
        for (bad in listOf(entry(added = 2), entry(added = 0), entry(added = 1, removed = 1), entry(added = 1, epoch = 2))) {
            val discard = assertIs<CommitDecision.Discard>(judge(commit(added = listOf(leaf("carol", 3))), bad))
            assertEquals(GroupStatus.FROZEN_ENVELOPE_MISMATCH, discard.status)
        }
    }

    @Test
    fun aRemovedLeafMustBeOneOfTheRoster() {
        assertIs<CommitDecision.Merge>(judge(commit(removed = listOf(bob)), entry(removed = 1)))
        val discard = assertIs<CommitDecision.Discard>(judge(commit(removed = listOf(leaf("zed", 9))), entry(removed = 1)))
        assertEquals(GroupStatus.FROZEN_ENVELOPE_MISMATCH, discard.status)
    }

    @Test
    fun aContactWithAnotherKeyHoldsTheCommit() {
        val pins = ContactPins { name, _ -> if (name == "carol") PinCheck.MISMATCH else PinCheck.STRANGER }
        val discard = assertIs<CommitDecision.Discard>(judge(commit(added = listOf(leaf("carol", 3))), entry(added = 1), pins))
        assertEquals(GroupStatus.FROZEN_CONTACT_MISMATCH, discard.status)
    }

    @Test
    fun aStrangerWithAKnownNameIsFlaggedButAPinnedKeyIsNot() {
        val second = leaf("bob", 7)
        val flagged = assertIs<CommitDecision.Merge>(judge(commit(added = listOf(second)), entry(added = 1)))
        assertEquals(listOf(KeyBytes(second.signatureKey)), flagged.unverified)
        val pinned = ContactPins { _, _ -> PinCheck.PINNED }
        assertEquals(
            emptyList(),
            assertIs<CommitDecision.Merge>(judge(commit(added = listOf(second)), entry(added = 1), pinned)).unverified,
        )
    }
}
