package app.gifitup.shared.domain.messages

import app.gifitup.shared.data.content.Content
import app.gifitup.shared.data.content.ContentCodec
import app.gifitup.shared.data.content.Decoded
import app.gifitup.shared.domain.contacts.KeyBytes
import app.gifitup.shared.domain.groups.DeliveredMessage
import app.gifitup.shared.domain.groups.IncomingSink

/**
 * Turns what the sync engine decrypted into stored messages (ADR-0006 B5, B9, B10). Reading never fails: whatever the
 * bytes are, the message is stored with a body or, for an annotation that is dropped, not stored at all and still
 * counted as processed, so the engine acknowledges it and never retries it. The group is the one the engine processed
 * the message in; nothing here reads a group id from the server.
 */
class MessageIntake(
    private val store: MessageStore,
) : IncomingSink {
    override suspend fun deliver(message: DeliveredMessage) {
        val body = bodyOf(message.plaintext) ?: return
        store.insert(
            ChatMessage(KeyBytes(message.groupId), KeyBytes(message.messageRef), message.senderUsername, message.seq, body, null),
        )
    }

    companion object {
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
                    }
                }
            }
    }
}
