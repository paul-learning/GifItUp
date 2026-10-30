package app.gifitup.shared.domain.messages

import app.gifitup.shared.data.content.ContentCodec
import app.gifitup.shared.data.content.GifReference
import app.gifitup.shared.data.network.Session
import app.gifitup.shared.domain.auth.AuthResult
import app.gifitup.shared.domain.contacts.KeyBytes
import app.gifitup.shared.domain.groups.GroupChat
import app.gifitup.shared.domain.groups.IncomingSink
import app.gifitup.shared.domain.groups.SendObserver
import app.gifitup.shared.domain.groups.SendState
import app.gifitup.shared.domain.groups.SyncOutcome
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Sends a GIF (ADR-0003 Mode B′, ADR-0006): the reference the picker validated and an optional caption become content
 * bytes, the sync engine seals, stores and posts them, and the message appears in the [MessageStore] as
 * [DeliveryState.SENDING] the moment it is stored, then as [DeliveryState.SENT] or [DeliveryState.FAILED]. A caption
 * that breaks the text rules sends nothing ([SyncOutcome.InvalidRequest]). One message is in flight per group; while
 * an older one is unsettled it is posted again first, its state follows the answer, and if it did not go through the new
 * one is not sent and not shown ([SyncOutcome.PendingFirst]). A message for a group the store has no room for is sent but
 * not shown.
 *
 * The history follows the engine's stored state, never a guess (ADR-0017): the SENDING record is stored when the message
 * is sealed and **before** anything is persisted or posted (if that fails, nothing is sent); a state changes only on the
 * answer of the message's own reference; and at the start of a send or a resume every SENDING record that is neither the
 * pending message nor the last accepted one becomes FAILED (sealed but never stored, or never posted), the last accepted
 * one becomes SENT. A history write that fails after the server answered is left for that reconcile; it never fails the
 * call, and no result other than [SyncOutcome.Sent] and [SyncOutcome.Rejected] ever changes a state.
 */
class SendGif(
    private val chat: GroupChat,
    private val store: MessageStore,
    private val nowEpochSeconds: () -> Long,
) {
    /** Sends and resumes of this use case run one at a time, so the reconcile and the answer see the same state. */
    private val mutex = Mutex()

    suspend operator fun invoke(
        session: Session,
        groupId: ByteArray,
        reference: GifReference,
        caption: String?,
        sink: IncomingSink,
    ): AuthResult<SyncOutcome> = mutex.withLock { send(session, groupId, reference, caption, sink) }

    private suspend fun send(
        session: Session,
        groupId: ByteArray,
        reference: GifReference,
        caption: String?,
        sink: IncomingSink,
    ): AuthResult<SyncOutcome> {
        val content = ContentCodec.encodeGif(reference, caption) ?: return AuthResult.Ok(SyncOutcome.InvalidRequest)
        val group = KeyBytes(groupId)
        var sealed: KeyBytes? = null
        val observer =
            object : SendObserver {
                override suspend fun sealed(messageRef: ByteArray) {
                    val ref = KeyBytes(messageRef)
                    // Shown the way a recipient will read it (trimmed caption), from the bytes that are sent. A failing
                    // insert aborts the send: the engine does not persist or post what the history could not store.
                    val body = MessageIntake.bodyOf(content) ?: MessageBody.Unavailable
                    store.insert(ChatMessage(group, ref, null, null, body, DeliveryState.SENDING, nowEpochSeconds()))
                    sealed = ref
                }

                override suspend fun reconcile(state: SendState) = reconcileGroup(group, state)

                override suspend fun earlierSettled(
                    reference: ByteArray?,
                    outcome: SyncOutcome,
                ) {
                    // Not swallowed: an accepted older message whose SENT cannot be written stops the new send.
                    reference?.let { settleOrThrow(group, KeyBytes(it), outcome) }
                }
            }
        val result = chat.send(session, groupId, content, sink, observer)
        if (result is AuthResult.Ok) sealed?.let { settle(group, it, result.value) }
        return result
    }

    /** Posts the group's unsettled message again; its state follows the answer. */
    suspend fun resume(
        session: Session,
        groupId: ByteArray,
        sink: IncomingSink,
    ): AuthResult<SyncOutcome> =
        mutex.withLock {
            val group = KeyBytes(groupId)
            var resumed: KeyBytes? = null
            val observer =
                object : SendObserver {
                    override suspend fun reconcile(state: SendState) {
                        resumed = state.pending?.let(::KeyBytes)
                        reconcileGroup(group, state)
                    }
                }
            val result = chat.resume(session, groupId, sink, observer)
            if (result is AuthResult.Ok) resumed?.let { settle(group, it, result.value) }
            result
        }

    /**
     * Settles the group's SENDING records against the engine's stored state: the pending one stays, the last accepted
     * one becomes SENT, any other is FAILED. A store that fails here leaves them for the next reconcile.
     */
    private suspend fun reconcileGroup(
        group: KeyBytes,
        state: SendState,
    ) {
        // A pending message from before references existed: nothing can be told apart, so nothing is touched.
        if (state.pendingUnknown) return
        val pending = state.pending?.let(::KeyBytes)
        val lastSent = state.lastSent?.let(::KeyBytes)
        // A store that fails here throws: the engine then sends nothing new, so a record left SENDING is never outlived by a
        // newer "last accepted" send (it would be FAILED though it was delivered).
        store.messages(group).filter { it.state == DeliveryState.SENDING && it.reference != pending }.forEach { message ->
            if (message.reference == lastSent && state.lastSentSeq != null) {
                store.setState(group, message.reference, DeliveryState.SENT, state.lastSentSeq)
            } else {
                store.setState(group, message.reference, DeliveryState.FAILED)
            }
        }
    }

    /** The state follows the server's answer to this message and nothing else; a failing store leaves it for the reconcile. */
    private suspend fun settle(
        group: KeyBytes,
        reference: KeyBytes,
        outcome: SyncOutcome,
    ) {
        try {
            settleOrThrow(group, reference, outcome)
        } catch (_: HistoryStoreException) {
            // The message was answered; its record is settled by the next reconcile (SENT through the last accepted send).
        }
    }

    private suspend fun settleOrThrow(
        group: KeyBytes,
        reference: KeyBytes,
        outcome: SyncOutcome,
    ) {
        when (outcome) {
            is SyncOutcome.Sent -> store.setState(group, reference, DeliveryState.SENT, outcome.seq)
            is SyncOutcome.Rejected -> store.setState(group, reference, DeliveryState.FAILED)
            else -> Unit
        }
    }
}
