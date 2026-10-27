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
        onSealed: suspend (ByteArray) -> Unit = {},
    ): SyncOutcome {
        val record = activeRecord(store, id)
        if (plaintext.size > MAX_CONTENT) halt(SyncOutcome.TooLong)
        if (record.pending?.kind == MessageKind.COMMIT) halt(SyncOutcome.CommitPending)
        // Fresh first, then settle: re-posting an old pending message into a log that already dropped it would be
        // accepted as new and make a stale state look fresh (SEC-1).
        poster.checkFresh(id, record.pending)
        record.pending?.let { settle(id, it) }
        if (store.recordOf(id).ownSeqs.size >= SyncStateCodec.MAX_OWN_SEQS) halt(SyncOutcome.Busy)
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
        notify(onSealed, sealed.messageRef)
        return post(id, pending)
    }

    /** Tells the caller the message reference once the message is stored; a failing observer never stops the send. */
    @Suppress("TooGenericExceptionCaught") // Only a history record hangs on it; the message is stored and gets posted anyway.
    private suspend fun notify(
        onSealed: suspend (ByteArray) -> Unit,
        messageRef: ByteArray,
    ) {
        try {
            onSealed(messageRef)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (ignored: Exception) {
            // The history is not part of the send.
        }
    }

    /** Posts the pending message again (a fresh start, or after [SyncOutcome.InFlight]); nothing pending is nothing to do. */
    suspend fun resume(id: KeyBytes): SyncOutcome {
        val pending = activeRecord(store, id).pending
        if (pending != null) poster.checkFresh(id, pending)
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

    /** Settles an older pending message first (one send in flight per group); if it does not go through, the new text waits. */
    private suspend fun settle(
        id: KeyBytes,
        pending: PendingSend,
    ) {
        val outcome = post(id, pending)
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

/** The record of a group that is syncing; otherwise the call ends here. */
internal fun activeRecord(
    store: SyncStore,
    id: KeyBytes,
): GroupRecord {
    val record = store.find(id) ?: halt(SyncOutcome.NotJoined)
    if (record.status != GroupStatus.ACTIVE) halt(SyncOutcome.Stopped(record.status))
    return record
}
