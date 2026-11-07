package app.gifitup.shared.domain.messages

import app.gifitup.shared.data.content.Content
import app.gifitup.shared.data.content.ContentCodec
import app.gifitup.shared.data.content.Decoded
import app.gifitup.shared.domain.contacts.KeyBytes
import app.gifitup.shared.domain.groups.DeliveredMessage
import app.gifitup.shared.domain.groups.IncomingSink
import app.gifitup.shared.domain.groups.MembershipChange

/**
 * Turns what the sync engine decrypted into stored messages (ADR-0006 B5, B9, B10). Reading never fails: whatever the
 * bytes are, the message is stored with a body or, for an annotation that is dropped, not stored at all and still
 * counted as processed, so the engine acknowledges it and never retries it. The group is the one the engine processed
 * the message in; nothing here reads a group id from the server.
 */
class MessageIntake(
    private val store: MessageStore,
    private val nowEpochSeconds: () -> Long,
) : IncomingSink {
    override suspend fun deliver(message: DeliveredMessage) {
        // A reaction is not a message: it changes the reactions of its target, if the target is a GIF of this group (B10a). The
        // reactor is the MLS credential's username; nothing about who reacted is read from the content.
        val reaction = (ContentCodec.decode(message.plaintext) as? Decoded.Message)?.content as? Content.Reaction
        if (reaction != null) {
            store.react(KeyBytes(message.groupId), reaction.target, message.senderUsername, reaction.emoji)
            return
        }
        val body = bodyOf(message.plaintext) ?: return
        store.insert(
            ChatMessage(
                KeyBytes(message.groupId),
                KeyBytes(message.messageRef),
                message.senderUsername,
                message.seq,
                body,
                null,
                nowEpochSeconds(),
            ),
        )
    }

    /**
     * A roster change the engine processed (#31): one notice per changed leaf (or one "joined"), each under a reference that
     * is a pure function of group, epoch, event and leaf, so the same change twice is one notice. The reference starts with
     * 0xFE and is padded with zeros: a message reference is a SHA-256 value, so no real message can have this shape.
     */
    override suspend fun membership(change: MembershipChange) {
        val group = KeyBytes(change.groupId)
        val notices =
            change.added.map { notice(group, change, MembershipEvent.ADDED, it.username, it.leafIndex) } +
                change.removed.map { notice(group, change, MembershipEvent.REMOVED, it.username, it.leafIndex) } +
                listOfNotNull(change.joined.takeIf { it > 0 }?.let { joined(group, change, it) })
        notices.forEach { store.insert(it) }
    }

    private fun notice(
        group: KeyBytes,
        change: MembershipChange,
        event: MembershipEvent,
        subject: String,
        leaf: Int,
    ): ChatMessage {
        // A name that breaks the username rule is never shown: the notice says only that the members changed (SEC-11).
        val body = MessageBody.Membership.of(event, subject, change.by) ?: fallback(change)
        return notice(group, noticeRef(event, change.epoch, leaf), change.by, body)
    }

    /** Ours (no sender) carry a delivery state like our messages do (the record layout needs it); a received one has a sender. */
    private fun notice(
        group: KeyBytes,
        reference: KeyBytes,
        by: String?,
        body: MessageBody.Membership,
    ) = ChatMessage(group, reference, by, null, body, if (by == null) DeliveryState.SENT else null, nowEpochSeconds())

    private fun fallback(change: MembershipChange): MessageBody.Membership =
        checkNotNull(
            MessageBody.Membership.of(MembershipEvent.CHANGED, "", change.by)
                ?: MessageBody.Membership.of(MembershipEvent.CHANGED, "", null),
        )

    private fun joined(
        group: KeyBytes,
        change: MembershipChange,
        count: Int,
    ): ChatMessage? {
        val body =
            MessageBody.Membership.of(MembershipEvent.JOINED, "", change.by, count)
                ?: MessageBody.Membership.of(MembershipEvent.JOINED, "", null, count)
        // The epoch field of a join is the join point (the seq this device joined at): a later re-add is a new notice (SEC-13).
        return body?.let {
            ChatMessage(
                group,
                noticeRef(MembershipEvent.JOINED, change.epoch, 0),
                change.by,
                null,
                it,
                null,
                nowEpochSeconds(),
            )
        }
    }

    companion object {
        private const val NOTICE_MARK: Byte = 0xFE.toByte()
        private const val REF_SIZE = 32

        /** 0xFE ‖ event ‖ epoch(8) ‖ leaf(4) ‖ zeros. */
        fun noticeRef(
            event: MembershipEvent,
            epoch: Long,
            leaf: Int,
        ): KeyBytes {
            val out = ByteArray(REF_SIZE)
            out[0] = NOTICE_MARK
            out[1] = (event.ordinal + 1).toByte()
            for (i in 0 until Long.SIZE_BYTES) out[2 + i] = (epoch shr (Long.SIZE_BITS - Byte.SIZE_BITS * (i + 1))).toByte()
            for (i in 0 until Int.SIZE_BYTES) out[2 + Long.SIZE_BYTES + i] = (leaf shr (Int.SIZE_BITS - Byte.SIZE_BITS * (i + 1))).toByte()
            return KeyBytes(out)
        }

        /** What [plaintext] shows, or `null` for a dropped annotation. */
        fun bodyOf(plaintext: ByteArray): MessageBody? =
            when (val decoded = ContentCodec.decode(plaintext)) {
                Decoded.Dropped -> {
                    null
                }

                Decoded.Invalid -> {
                    MessageBody.Unavailable
                }

                Decoded.Unsupported -> {
                    MessageBody.UpdateNeeded
                }

                is Decoded.Message -> {
                    when (val content = decoded.content) {
                        is Content.Gif -> MessageBody.Gif(content.reference, content.caption)

                        is Content.GroupMeta -> MessageBody.GroupMeta(content.name)

                        // Applied by [deliver] before this is reached; a reaction has no body of its own.
                        is Content.Reaction -> null
                    }
                }
            }
    }
}
