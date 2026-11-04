package app.gifitup.shared.domain.groups

import app.gifitup.shared.data.crypto.IncomingMessage
import app.gifitup.shared.data.crypto.LeafInfo
import app.gifitup.shared.domain.contacts.KeyBytes
import kotlin.coroutines.cancellation.CancellationException

/**
 * Tells the [IncomingSink] which members a commit added and removed (#31): only for a commit that was **merged**, before
 * the cursor moves, and idempotent for the same change. A failed store halts like any message.
 */
internal class MembershipAnnouncer(
    private val sink: IncomingSink,
) {
    /** A received commit that [MessageReceiver] merged: the sender is the MLS leaf that committed. */
    suspend fun merged(
        id: KeyBytes,
        commit: IncomingMessage.Commit,
    ) {
        if (commit.added.isEmpty() && commit.removed.isEmpty()) return
        tell(
            MembershipChange(
                id.bytes,
                commit.epoch,
                commit.sender.username,
                commit.added.map { MemberChange(it.username, it.leafIndex) },
                commit.removed.map { MemberChange(it.username, it.leafIndex) },
            ),
        )
    }

    /**
     * A commit **this device made** ([MembershipChange.by] is `null`), from the roster before and after its own merge. A
     * creation is not announced (the caller skips it).
     */
    suspend fun own(
        id: KeyBytes,
        epoch: Long,
        before: List<LeafInfo>,
        after: List<LeafInfo>,
    ) {
        val was = before.map { KeyBytes(it.signatureKey) }.toSet()
        val now = after.map { KeyBytes(it.signatureKey) }.toSet()
        // The leaf's place in the list stands in for its index: it is stable for this change and distinct within it.
        val added = after.filter { KeyBytes(it.signatureKey) !in was }.mapIndexed { i, leaf -> MemberChange(leaf.username, i) }
        val removed = before.filter { KeyBytes(it.signatureKey) !in now }.mapIndexed { i, leaf -> MemberChange(leaf.username, i) }
        if (added.isNotEmpty() || removed.isNotEmpty()) tell(MembershipChange(id.bytes, epoch, null, added, removed))
    }

    private suspend fun tell(change: MembershipChange) {
        try {
            sink.membership(change)
        } catch (e: CancellationException) {
            throw e
        } catch (ignored: Exception) {
            halt(SyncOutcome.StorageFailed)
        }
    }
}
