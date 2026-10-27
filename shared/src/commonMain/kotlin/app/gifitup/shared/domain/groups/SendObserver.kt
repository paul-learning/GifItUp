package app.gifitup.shared.domain.groups

/**
 * What a caller of [GroupSyncEngine.send] can learn while the engine works (for a history that shows delivery states).
 * Both calls come from inside the send, under the engine's lock; a failing observer never stops the send.
 */
interface SendObserver {
    /** The new message is sealed and stored, and not posted yet; [messageRef] is its message reference (ADR-0006 B9). */
    suspend fun sealed(messageRef: ByteArray) {}

    /**
     * An older message of the group was still pending and has been posted again before the new one: [outcome] is its
     * answer ([SyncOutcome.Sent], [SyncOutcome.InFlight] or [SyncOutcome.Rejected]). If it did not go through, the new
     * message is not sent.
     */
    suspend fun earlierSettled(outcome: SyncOutcome) {}

    companion object {
        val None: SendObserver = object : SendObserver {}
    }
}
