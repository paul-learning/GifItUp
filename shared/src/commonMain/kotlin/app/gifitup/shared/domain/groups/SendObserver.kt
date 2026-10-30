package app.gifitup.shared.domain.groups

/**
 * What a caller of [GroupSyncEngine.send] and [GroupSyncEngine.resume] learns while the engine works, and the place the
 * caller's history hooks in (ADR-0017). All calls come from inside the call, under the engine's lock.
 *
 * Only [sealed] is part of the send: if it throws, nothing is stored or posted and the engine is dead for the rest of
 * the call. [reconcile] and [earlierSettled] are bookkeeping: a failing one never stops the send (only a cancellation
 * does), and what it could not write is retried by the next [reconcile].
 */
interface SendObserver {
    /**
     * The new message is sealed and not stored in the snapshot or posted yet; [messageRef] is its message reference
     * (ADR-0006 B9). The caller stores its history record here. Throwing aborts the send.
     */
    suspend fun sealed(messageRef: ByteArray) {}

    /**
     * Called first in `send` and `resume` of a group with no commit pending (and whose state passed the freshness check):
     * the stored state of the group's application messages, so the caller can settle its history. A message the caller
     * still shows as sending is the pending one ([SendState.pending]), the last accepted one ([SendState.lastSent]) or
     * neither (sealed but never stored, or never posted).
     */
    suspend fun reconcile(state: SendState) {}

    /**
     * An older message of the group was still pending and has been posted again before the new one: [reference] names it
     * (`null` for one stored before references existed), [outcome] is its answer ([SyncOutcome.Sent], [SyncOutcome.InFlight]
     * or [SyncOutcome.Rejected]). If it did not go through, the new message is not sent.
     */
    suspend fun earlierSettled(
        reference: ByteArray?,
        outcome: SyncOutcome,
    ) {}

    companion object {
        val None: SendObserver = object : SendObserver {}
    }
}

/** What the group's stored state says about its application messages (see [SendObserver.reconcile]). */
class SendState(
    /** The reference of the message stored and not yet accepted, or `null` (none, or one without a reference). */
    val pending: ByteArray?,
    /** The reference and seq of the last message the server accepted, or `null`. */
    val lastSent: ByteArray?,
    val lastSentSeq: Long?,
) {
    override fun toString(): String = "SendState(pending=${pending != null}, lastSent=${lastSent != null})"
}
