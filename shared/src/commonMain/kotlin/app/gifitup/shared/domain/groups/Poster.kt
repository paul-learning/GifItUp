package app.gifitup.shared.domain.groups

import app.gifitup.shared.data.crypto.MlsClient
import app.gifitup.shared.data.network.ApiError
import app.gifitup.shared.data.network.ApiResult
import app.gifitup.shared.data.network.DeliveryEnvelope
import app.gifitup.shared.data.network.MessageKind
import app.gifitup.shared.domain.contacts.KeyBytes

/** What the Delivery Service said to a post. Nothing is changed here: each side applies its own consequences. */
internal sealed interface Posted {
    class Accepted(
        val seq: Long,
    ) : Posted

    /** The server refuses it for good; a repeat can't change that. */
    class Refused(
        val error: ApiError,
    ) : Posted

    /** A commit's epoch is not the group's any more: others committed first. */
    data object Conflict : Posted

    /** No usable answer (network, rate limit, full log): the message stays pending. */
    class Unknown(
        val error: ApiError,
    ) : Posted
}

/**
 * The shared part of posting a stored message (see [SendSide] and [CommitSide]): the freshness check, the post itself,
 * and the bookkeeping once the server accepted it. A group the server no longer shows us ends the call as "no longer
 * available".
 */
internal class Poster(
    private val mls: MlsClient,
    private val delivery: DeliveryPort,
    private val store: SyncStore,
) {
    /**
     * The server's last client message id for this device must be the last one the server accepted from us, or the one
     * we have pending (its answer may have been lost). Otherwise this state is older than a send the server accepted:
     * the group becomes read-only and nothing is sent (ADR-0002, ADR-0016).
     */
    suspend fun checkFresh(
        id: KeyBytes,
        pending: PendingSend?,
    ) {
        val state = store.answerForGroup(mls, id, delivery.status(id.bytes))
        val mine = store.recordOf(id).lastMsgId
        val theirs = state.lastClientMsgId
        val fresh = sameId(theirs, mine) || (pending != null && sameId(theirs, pending.clientMsgId))
        if (!fresh) store.freeze(id, GroupStatus.READ_ONLY_ROLLBACK)
    }

    /** Posts the stored message. A commit at epoch 0 is the creation of the group; every other one is a normal post. */
    suspend fun post(
        id: KeyBytes,
        pending: PendingSend,
    ): Posted {
        val envelope = envelopeOf(pending)
        val creating = pending.kind == MessageKind.COMMIT && pending.epoch == 0L
        val result = if (creating) delivery.createGroup(id.bytes, envelope) else delivery.post(id.bytes, envelope)
        return when (result) {
            is ApiResult.Ok -> Posted.Accepted(result.value.seq)
            is ApiResult.Failed -> classify(id, result.error)
        }
    }

    private suspend fun classify(
        id: KeyBytes,
        error: ApiError,
    ): Posted {
        if (error == ApiError.NOT_FOUND) {
            store.markGone(mls, id)
            halt(SyncOutcome.Stopped(GroupStatus.REMOVED))
        }
        return when (error) {
            ApiError.EPOCH_CONFLICT -> Posted.Conflict
            in REFUSED -> Posted.Refused(error)
            else -> Posted.Unknown(error)
        }
    }

    /** The server accepted [pending] at [seq]: it is no longer pending, and the cursor will pass its seq without a decrypt. */
    suspend fun recordSent(
        id: KeyBytes,
        pending: PendingSend,
        seq: Long,
    ) {
        val record = store.recordOf(id)
        val own = (record.ownSeqs + listOfNotNull(OwnSend.of(seq, pending.payload))).takeLast(SyncStateCodec.MAX_OWN_SEQS)
        store.put(id, record.copy(lastMsgId = pending.clientMsgId, pending = null, ownSeqs = own))
        store.persist()
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
        /** Answers that no repeat can change. */
        val REFUSED =
            setOf(
                ApiError.ENVELOPE_INVALID,
                ApiError.PAYLOAD_TOO_LARGE,
                ApiError.BAD_REQUEST,
                ApiError.WELCOME_LIMIT,
                ApiError.GROUP_LIMIT,
            )
    }
}

internal fun sameId(
    a: ByteArray?,
    b: ByteArray?,
) = if (a == null || b == null) a == b else a.contentEquals(b)
