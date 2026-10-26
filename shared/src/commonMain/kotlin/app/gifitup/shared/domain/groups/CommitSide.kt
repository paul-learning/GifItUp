package app.gifitup.shared.domain.groups

import app.gifitup.shared.data.crypto.MlsClient
import app.gifitup.shared.data.crypto.MlsErrorCode
import app.gifitup.shared.data.crypto.MlsException
import app.gifitup.shared.data.crypto.PreparedCommit
import app.gifitup.shared.data.crypto.SecureRandomSource
import app.gifitup.shared.data.network.ApiError
import app.gifitup.shared.data.network.MessageKind
import app.gifitup.shared.domain.contacts.KeyBytes

/**
 * A claimed KeyPackage with the device it belongs to (the caller has checked it against the contact book, ADR-0014). The
 * engine cannot check that the device id and the KeyPackage belong together: the caller takes both from one contact-book
 * entry.
 */
class NewMember(
    val deviceId: String,
    val keyPackage: ByteArray,
) {
    override fun toString(): String = "NewMember(deviceId=$deviceId)"
}

/**
 * A device to remove: its id for the envelope and the signature key its leaf carries. The engine cannot check that they
 * belong together: the caller takes both from one contact-book entry (or the member list).
 */
class RemoveTarget(
    val deviceId: String,
    val signatureKey: ByteArray,
) {
    override fun toString(): String = "RemoveTarget(deviceId=$deviceId)"
}

/** A new group: [groupId] is set as soon as the group exists on this device, even if the post's outcome is still open. */
class GroupCreation(
    val groupId: ByteArray?,
    val outcome: SyncOutcome,
)

/**
 * Commits (ADR-0016): creating a group and adding or removing members. A commit is prepared by the crate (held pending
 * inside its state), stored with the snapshot, posted, and merged only after the Delivery Service accepted it. Before the
 * merge it reads what was posted ahead of it, because merging ends the epoch those messages belong to. An
 * `epoch_conflict` clears the commit, applies the winner and prepares again, three times at most.
 */
internal class CommitSide(
    private val mls: MlsClient,
    private val store: SyncStore,
    private val poster: Poster,
    private val receiver: MessageReceiver,
    private val random: SecureRandomSource,
) {
    suspend fun create(members: List<NewMember>): GroupCreation {
        if (members.size !in 1..MAX_CHANGES || !members.all { isDeviceId(it.deviceId) }) halt(SyncOutcome.InvalidRequest)
        val groupId = crate { mls.createGroup() }
        val id = KeyBytes(groupId)
        val prepared =
            try {
                userInput { mls.prepareAdd(groupId, members.map { it.keyPackage }) }
            } catch (stop: Halt) {
                mls.forgetQuietly(groupId) // nothing was stored; the group the crate made for this call must not stay behind
                throw stop
            }
        val pending = pendingOf(prepared, members.map { it.deviceId }, emptyList())
        store.put(id, GroupRecord.joinedAt(0, emptyList()).copy(pending = pending))
        store.persist()
        return GroupCreation(groupId, finish(id, pending) ?: SyncOutcome.InFlight(ApiError.EPOCH_CONFLICT))
    }

    suspend fun add(
        id: KeyBytes,
        members: List<NewMember>,
    ): SyncOutcome {
        if (members.size !in 1..MAX_CHANGES || !members.all { isDeviceId(it.deviceId) }) halt(SyncOutcome.InvalidRequest)
        return change(id, members.map { it.deviceId }, emptyList()) { mls.prepareAdd(id.bytes, members.map { it.keyPackage }) }
    }

    suspend fun remove(
        id: KeyBytes,
        targets: List<RemoveTarget>,
    ): SyncOutcome {
        if (targets.size !in 1..MAX_CHANGES || !targets.all { isDeviceId(it.deviceId) }) halt(SyncOutcome.InvalidRequest)
        return change(id, emptyList(), targets.map { it.deviceId }) {
            val roster = mls.members(id.bytes)
            val leaves = targets.map { target -> roster.firstOrNull { it.signatureKey.contentEquals(target.signatureKey) }?.leafIndex }
            if (leaves.any { it == null }) throw MlsException(MlsErrorCode.InvalidInput)
            mls.prepareRemove(id.bytes, leaves.filterNotNull())
        }
    }

    /** Posts the pending commit again, unchanged: a lost answer, or a restart with a commit still pending. */
    suspend fun resume(id: KeyBytes): SyncOutcome {
        val pending = activeRecord(store, id).pending ?: halt(SyncOutcome.CaughtUp(0))
        // A creation has nothing to be fresh against: the server may never have seen the group (a lost request), and
        // asking it would read "not found" as "removed".
        if (pending.epoch != 0L) poster.checkFresh(id, pending)
        return finish(id, pending) ?: SyncOutcome.Rejected(ApiError.EPOCH_CONFLICT)
    }

    private suspend fun change(
        id: KeyBytes,
        added: List<String>,
        removed: List<String>,
        prepare: () -> PreparedCommit,
    ): SyncOutcome {
        repeat(MAX_TRIES) {
            val record = activeRecord(store, id)
            if (record.pending != null) halt(SyncOutcome.CommitPending)
            // Fresh before anything is read: a stale state must not process a log it has already used the ratchets of.
            poster.checkFresh(id, null)
            val outcome = receiver.sync(id)
            if (outcome !is SyncOutcome.CaughtUp) halt(outcome)
            if (store.recordOf(id).ownSeqs.size >= SyncStateCodec.MAX_OWN_SEQS) halt(SyncOutcome.Busy)
            val pending = pendingOf(userInput(prepare), added, removed)
            store.put(id, store.recordOf(id).copy(pending = pending))
            fitsOrDrop(id)
            store.persist()
            finish(id, pending)?.let { return it }
        }
        return SyncOutcome.Unavailable(ApiError.EPOCH_CONFLICT)
    }

    /** The stored state must have room for the pending commit and its welcome; if not, the commit is dropped again. */
    private fun fitsOrDrop(id: KeyBytes) {
        try {
            SyncStateCodec.encode(store.state)
        } catch (ignored: SyncStateException) {
            crate { mls.clearPending(id.bytes) }
            store.put(id, store.recordOf(id).copy(pending = null))
            halt(SyncOutcome.Busy)
        }
    }

    /** The server's answer to the pending commit; `null` means it lost the race for its epoch (already cleared). */
    private suspend fun finish(
        id: KeyBytes,
        pending: PendingSend,
    ): SyncOutcome? =
        when (val posted = poster.post(id, pending)) {
            is Posted.Accepted -> {
                accepted(id, pending, posted.seq)
            }

            is Posted.Refused -> {
                dropCommit(id)
                SyncOutcome.Rejected(posted.error)
            }

            Posted.Conflict -> {
                dropCommit(id)
                null
            }

            is Posted.Unknown -> {
                SyncOutcome.InFlight(posted.error)
            }
        }

    private suspend fun accepted(
        id: KeyBytes,
        pending: PendingSend,
        seq: Long,
    ): SyncOutcome {
        if (pending.epoch != 0L) {
            val outcome = receiver.sync(id, upTo = seq - 1)
            if (outcome !is SyncOutcome.CaughtUp) halt(outcome)
        }
        crate { mls.mergePending(id.bytes) }
        poster.recordSent(id, pending, seq)
        return SyncOutcome.Sent(seq)
    }

    /** The commit will not be merged: the crate forgets it (a creation also forgets the group), then the record. */
    private suspend fun dropCommit(id: KeyBytes) {
        if (store.recordOf(id).pending?.epoch == 0L) {
            crate { mls.forgetGroup(id.bytes) }
            store.drop(id)
        } else {
            crate { mls.clearPending(id.bytes) }
            store.put(id, store.recordOf(id).copy(pending = null))
        }
        store.persist()
    }

    private fun pendingOf(
        prepared: PreparedCommit,
        added: List<String>,
        removed: List<String>,
    ) = PendingSend(
        random.nextBytes(SyncStateCodec.MSG_ID_BYTES),
        MessageKind.COMMIT,
        prepared.epoch,
        added,
        removed,
        prepared.welcome,
        prepared.commit,
    )

    private companion object {
        const val MAX_CHANGES = 10
        const val MAX_TRIES = 3
    }
}

/** Runs a crate call that takes the caller's input: a dead client halts, any other refusal is [SyncOutcome.InvalidRequest]. */
internal fun <T> userInput(call: () -> T): T =
    try {
        call()
    } catch (e: MlsException) {
        halt(
            when (e.code) {
                MlsErrorCode.Poisoned -> SyncOutcome.Poisoned(null)
                MlsErrorCode.PendingOperation -> SyncOutcome.CommitPending
                else -> SyncOutcome.InvalidRequest
            },
        )
    }

private val DEVICE_ID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

/** A device id as the server hands them out: a canonical lowercase UUID (it is stored with the pending commit). */
private fun isDeviceId(value: String) = DEVICE_ID.matches(value)

private fun MlsClient.forgetQuietly(groupId: ByteArray) {
    try {
        forgetGroup(groupId)
    } catch (ignored: MlsException) {
        // A dead client is reported by the caller's halt; nothing else can be done for a group that was never stored.
    }
}
