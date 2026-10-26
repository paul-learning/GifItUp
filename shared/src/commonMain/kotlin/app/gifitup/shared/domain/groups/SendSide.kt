package app.gifitup.shared.domain.groups

import app.gifitup.shared.data.crypto.MlsClient
import app.gifitup.shared.data.crypto.SecureRandomSource
import app.gifitup.shared.data.network.ApiError
import app.gifitup.shared.data.network.ApiResult
import app.gifitup.shared.data.network.DeliveryEnvelope
import app.gifitup.shared.data.network.MessageKind
import app.gifitup.shared.domain.contacts.KeyBytes

/**
 * Send side of the group lifecycle (ADR-0016). A message is sealed, **stored with the snapshot**, and only then posted;
 * the server's answer moves it from pending to sent. A post whose outcome is unknown is posted again, unchanged and
 * with the same client message id, until the server answers (ADR-0015 makes the repeat safe). Before anything is sealed
 * the freshness check runs: the server's last client message id for this device must be the one we stored, otherwise
 * this state is older than a send the server accepted, and the group becomes read-only (rollback, ADR-0002).
 */
internal class SendSide(
    private val mls: MlsClient,
    private val delivery: DeliveryPort,
    private val store: SyncStore,
    private val random: SecureRandomSource,
) {
    suspend fun send(
        id: KeyBytes,
        plaintext: ByteArray,
    ): SyncOutcome {
        val record = activeRecord(id)
        if (plaintext.size > MAX_CONTENT) halt(SyncOutcome.TooLong)
        record.pending?.let { settle(id, it) }
        checkFresh(id)
        if (!fitsAnother(id)) halt(SyncOutcome.Busy)
        val sealed = crate { mls.encrypt(id.bytes, plaintext) }
        val pending =
            PendingSend(
                random.nextBytes(SyncStateCodec.MSG_ID_BYTES),
                MessageKind.APPLICATION,
                null,
                emptyList(),
                emptyList(),
                null,
                sealed.message,
            )
        store.put(id, store.recordOf(id).copy(pending = pending))
        store.persist()
        return post(id, pending)
    }

    /** Posts the pending message again (a fresh start, or after [SyncOutcome.InFlight]); nothing pending is nothing to do. */
    suspend fun resume(id: KeyBytes): SyncOutcome {
        val pending = activeRecord(id).pending
        return if (pending == null) SyncOutcome.CaughtUp(0) else post(id, pending)
    }

    /** Whether the stored state still has room for one more pending application message (checked before sealing). */
    private fun fitsAnother(id: KeyBytes): Boolean {
        val probe =
            PendingSend(
                ByteArray(SyncStateCodec.MSG_ID_BYTES),
                MessageKind.APPLICATION,
                null,
                emptyList(),
                emptyList(),
                null,
                ByteArray(MAX_PAYLOAD),
            )
        return try {
            SyncStateCodec.encode(store.state.with(id, store.recordOf(id).copy(pending = probe)))
            true
        } catch (ignored: SyncStateException) {
            false
        }
    }

    private fun activeRecord(id: KeyBytes): GroupRecord {
        val record = store.find(id) ?: halt(SyncOutcome.NotJoined)
        if (record.status != GroupStatus.ACTIVE) halt(SyncOutcome.Stopped(record.status))
        return record
    }

    /** Settles an older pending message first: one send in flight per group. */
    private suspend fun settle(
        id: KeyBytes,
        pending: PendingSend,
    ) {
        val outcome = post(id, pending)
        if (outcome !is SyncOutcome.Sent) halt(outcome)
    }

    /** The server's last client message id for this device must be the last one the server accepted from us. */
    private suspend fun checkFresh(id: KeyBytes) {
        val state = store.answerForGroup(mls, id, delivery.status(id.bytes))
        val mine = store.recordOf(id).lastMsgId
        val theirs = state.lastClientMsgId
        val fresh = if (mine == null) theirs == null else theirs != null && mine.contentEquals(theirs)
        if (!fresh) store.freeze(id, GroupStatus.READ_ONLY_ROLLBACK)
    }

    private suspend fun post(
        id: KeyBytes,
        pending: PendingSend,
    ): SyncOutcome {
        val envelope = envelopeOf(pending)
        return when (val result = delivery.post(id.bytes, envelope)) {
            is ApiResult.Ok -> sent(id, pending, result.value.seq)
            is ApiResult.Failed -> failed(id, result.error)
        }
    }

    private suspend fun sent(
        id: KeyBytes,
        pending: PendingSend,
        seq: Long,
    ): SyncOutcome {
        val record = store.recordOf(id)
        store.put(
            id,
            record.copy(
                lastMsgId = pending.clientMsgId,
                pending = null,
                ownSeqs = (record.ownSeqs + seq).takeLast(SyncStateCodec.MAX_OWN_SEQS),
            ),
        )
        store.persist()
        return SyncOutcome.Sent(seq)
    }

    private suspend fun failed(
        id: KeyBytes,
        error: ApiError,
    ): SyncOutcome {
        if (error == ApiError.NOT_FOUND) {
            store.markGone(mls, id)
            halt(SyncOutcome.Stopped(GroupStatus.REMOVED))
        }
        return if (error in REFUSED) {
            store.put(id, store.recordOf(id).copy(pending = null))
            store.persist()
            SyncOutcome.Rejected(error)
        } else {
            SyncOutcome.InFlight(error)
        }
    }

    private fun envelopeOf(pending: PendingSend) =
        DeliveryEnvelope(
            pending.clientMsgId,
            pending.kind,
            pending.epoch,
            pending.added,
            pending.removed,
            pending.welcome?.let { welcome -> pending.added.associateWith { welcome } }.orEmpty(),
            pending.payload,
        )

    private companion object {
        /** One padded block holds this much content (ADR-0006 B7). */
        const val MAX_CONTENT = 896

        /** An application message on the wire (ADR-0015). */
        const val MAX_PAYLOAD = 2 * 1024

        /** Answers that no repeat can change. */
        val REFUSED = setOf(ApiError.ENVELOPE_INVALID, ApiError.PAYLOAD_TOO_LARGE, ApiError.BAD_REQUEST)
    }
}
