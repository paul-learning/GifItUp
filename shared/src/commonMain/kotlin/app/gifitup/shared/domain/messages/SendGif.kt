package app.gifitup.shared.domain.messages

import app.gifitup.shared.data.content.ContentCodec
import app.gifitup.shared.data.content.GifReference
import app.gifitup.shared.data.network.Session
import app.gifitup.shared.domain.auth.AuthResult
import app.gifitup.shared.domain.contacts.KeyBytes
import app.gifitup.shared.domain.groups.GroupChat
import app.gifitup.shared.domain.groups.IncomingSink
import app.gifitup.shared.domain.groups.SendObserver
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
 */
class SendGif(
    private val chat: GroupChat,
    private val store: MessageStore,
    private val nowEpochSeconds: () -> Long,
) {
    /** Sends and resumes of this use case run one at a time, so "the unsettled message" is not a moving target. */
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
                    sealed = ref
                    // Shown the way a recipient will read it (trimmed caption), from the bytes that are sent.
                    val body = MessageIntake.bodyOf(content) ?: MessageBody.Unavailable
                    store.insert(ChatMessage(group, ref, null, null, body, DeliveryState.SENDING, nowEpochSeconds()))
                }

                // The older unsettled message was posted again first; it is the one in flight, so it is the last SENDING.
                override suspend fun earlierSettled(outcome: SyncOutcome) = settleUnsettled(group, outcome)
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
            val result = chat.resume(session, groupId, sink)
            if (result is AuthResult.Ok) settleUnsettled(KeyBytes(groupId), result.value)
            result
        }

    private suspend fun settleUnsettled(
        group: KeyBytes,
        outcome: SyncOutcome,
    ) {
        store.messages(group).lastOrNull { it.state == DeliveryState.SENDING }?.let { settle(group, it.reference, outcome) }
    }

    private suspend fun settle(
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
