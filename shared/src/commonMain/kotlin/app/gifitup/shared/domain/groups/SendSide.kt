package app.gifitup.shared.domain.groups

import app.gifitup.shared.data.crypto.MlsClient
import app.gifitup.shared.data.crypto.SecureRandomSource
import app.gifitup.shared.data.network.ApiError
import app.gifitup.shared.data.network.MessageKind
import app.gifitup.shared.domain.contacts.KeyBytes

/**
 * Send side for application messages (ADR-0016). A message is sealed, **stored with the snapshot**, and only then
 * posted; the server's answer moves it from pending to sent. A post whose outcome is unknown is posted again, unchanged
 * and with the same client message id, until the server answers (ADR-0015 makes the repeat safe). Before anything is
 * sealed the freshness check runs ([Poster.checkFresh]).
 */
internal class SendSide(
    private val mls: MlsClient,
    private val store: SyncStore,
    private val poster: Poster,
    private val random: SecureRandomSource,
) {
    suspend fun send(
        id: KeyBytes,
        plaintext: ByteArray,
        observer: SendObserver = SendObserver.None,
    ): SyncOutcome {
        val record = activeRecord(store, id)
        if (plaintext.size > MAX_CONTENT) halt(SyncOutcome.TooLong)
        if (record.pending?.kind == MessageKind.COMMIT) halt(SyncOutcome.CommitPending)
        // Fresh first, then settle: re-posting an old pending message into a log that already dropped it would be
        // accepted as new and make a stale state look fresh (SEC-1).
        poster.checkFresh(id, record.pending)
        // The history must be in line with the stored state before anything new is sealed or accepted: the next accepted
        // send overwrites "the last accepted one", and a record not settled by then would be FAILED though it was delivered.
        stored { observer.reconcile(stateOf(store.recordOf(id))) }
        record.pending?.let { settle(id, it, observer) }
        if (store.recordOf(id).ownSeqs.size >= SyncStateCodec.MAX_OWN_SEQS) halt(SyncOutcome.Busy)
        if (!fitsAnother(id)) halt(SyncOutcome.Busy)
        val sealed = crate { mls.encrypt(id.bytes, plaintext) }
        // The history record first (ADR-0017): if it cannot be stored, nothing is persisted or posted and the engine, which
        // has used up a generation, is dead.
        stored { observer.sealed(sealed.messageRef) }
        val pending =
            PendingSend(
                random.nextBytes(SyncStateCodec.MSG_ID_BYTES),
                MessageKind.APPLICATION,
                null,
                emptyList(),
                emptyList(),
                null,
                sealed.message,
                sealed.messageRef,
            )
        store.put(id, store.recordOf(id).copy(pending = pending))
        store.persist()
        return post(id, pending)
    }

    /** Runs bookkeeping of an observer: a failing one never stops the send (the next reconcile retries); a cancellation does. */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun observed(call: suspend () -> Unit) {
        try {
            call()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (ignored: Exception) {
            // The history bookkeeping is not part of the send.
        }
    }

    /** Runs the observer call the send depends on: a failure is a storage failure (nothing was stored or posted). */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun stored(call: suspend () -> Unit) {
        try {
            call()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (ignored: Exception) {
            halt(SyncOutcome.StorageFailed)
        }
    }

    /** Posts the pending message again (a fresh start, or after [SyncOutcome.InFlight]); nothing pending is nothing to do. */
    suspend fun resume(
        id: KeyBytes,
        observer: SendObserver = SendObserver.None,
    ): SyncOutcome {
        val pending = activeRecord(store, id).pending
        if (pending != null) poster.checkFresh(id, pending)
        stored { observer.reconcile(stateOf(store.recordOf(id))) }
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
                ByteArray(SyncStateCodec.KEY_BYTES),
            )
        return try {
            val record =
                store
                    .recordOf(
                        id,
                    ).copy(pending = probe, lastSentReference = ByteArray(SyncStateCodec.KEY_BYTES), lastSentSeq = Long.MAX_VALUE)
            SyncStateCodec.encode(store.state.with(id, record))
            true
        } catch (ignored: SyncStateException) {
            false
        }
    }

    /** Settles an older pending message first (one send in flight per group); if it does not go through, the new text waits. */
    private suspend fun settle(
        id: KeyBytes,
        pending: PendingSend,
        observer: SendObserver,
    ) {
        val outcome = post(id, pending)
        // An accepted older message must be settled in the history before the new one is sealed (see the reconcile above).
        if (outcome is SyncOutcome.Sent) {
            stored {
                observer.earlierSettled(pending.reference, outcome)
            }
        } else {
            observed { observer.earlierSettled(pending.reference, outcome) }
        }
        if (outcome !is SyncOutcome.Sent) halt(SyncOutcome.PendingFirst(outcome))
    }

    private suspend fun post(
        id: KeyBytes,
        pending: PendingSend,
    ): SyncOutcome =
        when (val posted = poster.post(id, pending)) {
            is Posted.Accepted -> {
                poster.recordSent(id, pending, posted.seq)
                SyncOutcome.Sent(posted.seq)
            }

            is Posted.Refused -> {
                store.put(id, store.recordOf(id).copy(pending = null))
                store.persist()
                SyncOutcome.Rejected(posted.error)
            }

            is Posted.Unknown -> {
                SyncOutcome.InFlight(posted.error)
            }

            // An application message has no epoch, so the server can't answer this; treat it as unknown.
            Posted.Conflict -> {
                SyncOutcome.InFlight(ApiError.EPOCH_CONFLICT)
            }
        }

    private companion object {
        /** One padded block holds this much content (ADR-0006 B7). */
        const val MAX_CONTENT = 896

        /** An application message on the wire (ADR-0015). */
        const val MAX_PAYLOAD = 2 * 1024
    }
}

private fun stateOf(record: GroupRecord) =
    SendState(
        record.pending?.reference,
        record.lastSentReference,
        record.lastSentSeq,
        pendingUnknown = record.pending != null && record.pending.reference == null,
    )

/** The record of a group that is syncing; otherwise the call ends here. */
internal fun activeRecord(
    store: SyncStore,
    id: KeyBytes,
): GroupRecord {
    val record = store.find(id) ?: halt(SyncOutcome.NotJoined)
    if (record.status != GroupStatus.ACTIVE) halt(SyncOutcome.Stopped(record.status))
    return record
}
