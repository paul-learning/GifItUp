package app.gifitup.shared.domain.groups

import app.gifitup.shared.data.crypto.IncomingMessage
import app.gifitup.shared.data.crypto.MlsClient
import app.gifitup.shared.data.crypto.MlsErrorCode
import app.gifitup.shared.data.crypto.MlsException
import app.gifitup.shared.data.network.ApiError
import app.gifitup.shared.data.network.ApiResult
import app.gifitup.shared.data.network.GroupMessageEntry
import app.gifitup.shared.data.network.MessageKind
import app.gifitup.shared.data.network.MessagePage
import app.gifitup.shared.domain.contacts.KeyBytes
import kotlin.coroutines.cancellation.CancellationException

/**
 * Pulls the log of one group in strict seq order and applies it (see [GroupSyncEngine] for the rules). Everything that
 * needs the network or the contact book is loaded before a commit is staged; after staging the decision is local.
 */
internal class MessageReceiver(
    private val mls: MlsClient,
    private val delivery: DeliveryPort,
    private val pinsSource: ContactPinsSource,
    private val sink: IncomingSink,
    private val store: SyncStore,
) {
    suspend fun sync(id: KeyBytes): SyncOutcome {
        val record = store.find(id) ?: halt(SyncOutcome.NotJoined)
        if (record.status != GroupStatus.ACTIVE) halt(SyncOutcome.Stopped(record.status))
        var applied = 0
        var caughtUp = false
        while (!caughtUp) {
            val page = fetchPage(id, store.recordOf(id).cursor)
            page.messages.forEach { entry ->
                handle(id, entry)
                applied++
            }
            if (page.messages.isNotEmpty()) store.answerForGroup(mls, id, delivery.ack(id.bytes, store.recordOf(id).cursor))
            caughtUp = page.messages.isEmpty() || store.recordOf(id).cursor >= page.lastSeq
        }
        return SyncOutcome.CaughtUp(applied)
    }

    private suspend fun fetchPage(
        id: KeyBytes,
        cursor: Long,
    ): MessagePage {
        val page = store.answerForGroup(mls, id, delivery.fetch(id.bytes, cursor))
        if (page.oldestSeq > cursor + 1 && page.lastSeq > cursor) store.freeze(id, GroupStatus.FROZEN_GAP)
        return page
    }

    private suspend fun handle(
        id: KeyBytes,
        entry: GroupMessageEntry,
    ) {
        val record = store.recordOf(id)
        when {
            entry.seq != record.cursor + 1 -> store.freeze(id, GroupStatus.FROZEN_GAP)
            entry.seq in record.quarantined || record.ownSeqs.any { it.matches(entry.seq, entry.payload) } -> store.advance(id, entry.seq)
            entry.kind == MessageKind.APPLICATION -> application(id, entry)
            else -> commit(id, entry)
        }
    }

    private suspend fun application(
        id: KeyBytes,
        entry: GroupMessageEntry,
    ) {
        val staged =
            try {
                mls.stageIncoming(id.bytes, entry.payload)
            } catch (e: MlsException) {
                applicationFailed(id, entry.seq, e.code)
                return
            }
        when (staged) {
            is IncomingMessage.Application -> {
                deliver(id, entry.seq, staged)
                store.advance(id, entry.seq)
            }

            // An application entry that carries a commit: the server mislabelled it; a commit can't be skipped.
            is IncomingMessage.Commit -> {
                crate { mls.discardStaged(staged.token) }
                store.freeze(id, GroupStatus.FROZEN_COMMIT_REFUSED)
            }
        }
    }

    private suspend fun deliver(
        id: KeyBytes,
        seq: Long,
        message: IncomingMessage.Application,
    ) {
        try {
            sink.deliver(
                DeliveredMessage(
                    id.bytes,
                    seq,
                    message.sender.username,
                    message.sender.signatureKey,
                    message.messageRef,
                    message.plaintext,
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (ignored: Exception) {
            halt(SyncOutcome.StorageFailed)
        }
    }

    /**
     * A dead client halts. A used-up generation (a ciphertext we already processed, delivered again) is skipped at
     * once and the sync goes on. Anything else is counted and halts with [SyncOutcome.Retry] until the third failure.
     */
    private suspend fun applicationFailed(
        id: KeyBytes,
        seq: Long,
        code: MlsErrorCode,
    ) {
        if (code == MlsErrorCode.Poisoned) halt(SyncOutcome.Poisoned(seq))
        if (code == MlsErrorCode.Replay) {
            store.quarantine(id, seq)
            store.advance(id, seq)
        } else {
            store.countFailure(id, seq)
            if (store.recordOf(id).cursor < seq) halt(SyncOutcome.Retry(seq))
        }
    }

    private suspend fun commit(
        id: KeyBytes,
        entry: GroupMessageEntry,
    ) {
        val pins = pinsSource.load() ?: halt(SyncOutcome.LookupIncomplete)
        val roster = crate { mls.members(id.bytes) }
        val staged =
            try {
                mls.stageIncoming(id.bytes, entry.payload)
            } catch (e: MlsException) {
                commitFailed(id, e.code)
            }
        if (staged !is IncomingMessage.Commit) store.freeze(id, GroupStatus.FROZEN_COMMIT_REFUSED)
        when (val decision = CommitJudge.judge(staged, entry, roster, pins)) {
            is CommitDecision.Merge -> {
                merge(id, entry.seq, staged, decision)
            }

            is CommitDecision.Discard -> {
                crate { mls.discardStaged(staged.token) }
                store.freeze(id, decision.status)
            }
        }
    }

    private suspend fun commitFailed(
        id: KeyBytes,
        code: MlsErrorCode,
    ): Nothing {
        if (code == MlsErrorCode.Poisoned) halt(SyncOutcome.Poisoned(null))
        store.freeze(id, if (code == MlsErrorCode.WrongEpoch) GroupStatus.FROZEN_GAP else GroupStatus.FROZEN_COMMIT_REFUSED)
    }

    private suspend fun merge(
        id: KeyBytes,
        seq: Long,
        commit: IncomingMessage.Commit,
        decision: CommitDecision.Merge,
    ) {
        try {
            mls.mergeStaged(commit.token)
        } catch (e: MlsException) {
            if (e.code == MlsErrorCode.Poisoned) halt(SyncOutcome.Poisoned(null))
            store.freeze(id, GroupStatus.FROZEN_COMMIT_REFUSED)
        }
        val record = store.recordOf(id)
        // Flags only ever name leaves of the group (at most 50), so keeping those still in the roster bounds the list.
        val present = crate { mls.members(id.bytes) }.map { KeyBytes(it.signatureKey) }.toSet()
        val unverified = (record.unverified + decision.unverified).distinct().filter { it in present }
        store.put(
            id,
            record.copy(
                cursor = seq,
                failures = record.failures - seq,
                unverified = unverified,
                ownSeqs = record.ownSeqs.filter { it.seq > seq },
            ),
        )
        if (commit.selfRemoved) store.markGone(mls, id) else store.persist()
    }
}
