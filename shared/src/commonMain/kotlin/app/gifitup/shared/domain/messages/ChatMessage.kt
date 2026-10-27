package app.gifitup.shared.domain.messages

import app.gifitup.shared.data.content.GifReference
import app.gifitup.shared.domain.contacts.KeyBytes

/**
 * What a message shows, decided once when it is read from the content (ADR-0006 B5). No variant carries received text
 * but a checked caption or name.
 */
sealed interface MessageBody {
    class Gif(
        val reference: GifReference,
        val caption: String?,
    ) : MessageBody

    class GroupMeta(
        val name: String,
    ) : MessageBody

    /** "Message unavailable": malformed, too big or a failed check. */
    data object Unavailable : MessageBody

    /** "Update the app": a newer content version or an unknown type. */
    data object UpdateNeeded : MessageBody
}

/** Where one of our own messages stands. A received message has none. */
enum class DeliveryState {
    /** Sealed and stored; the server's answer is not known yet (or the post has not happened). [SendGif.resume] posts it again. */
    SENDING,

    /** The Delivery Service accepted it. */
    SENT,

    /** The Delivery Service refused it for good. Nothing is pending; the user can send it again as a new message. */
    FAILED,
}

/**
 * One message of a group. [reference] is its message reference (ADR-0006 B9) and, with [groupId], its identity.
 * [sender] is the MLS-authenticated username of a received message, `null` for one of ours; [seq] is the server's
 * order number, a label for display order only, `null` while one of ours is not accepted yet.
 */
class ChatMessage(
    val groupId: KeyBytes,
    val reference: KeyBytes,
    val sender: String?,
    val seq: Long?,
    val body: MessageBody,
    val state: DeliveryState?,
) {
    override fun toString(): String = "ChatMessage(state=$state)"
}
