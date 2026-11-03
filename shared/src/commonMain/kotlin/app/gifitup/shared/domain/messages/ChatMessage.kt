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

    /**
     * A change of the group's members that this device processed (#31): [subject] was added or removed by [by], or this
     * device joined (then [subject] is empty and [count] is the roster size). [MembershipEvent.CHANGED] says only that the
     * members changed, for a change with a name that breaks the username rule (nothing is shown that could not be
     * checked). Derived from the accepted commit or welcome and stored locally; never sent. Usernames are the MLS
     * credentials' and pass the username rule again here.
     */
    class Membership private constructor(
        val event: MembershipEvent,
        val subject: String,
        val by: String?,
        val count: Int,
    ) : MessageBody {
        companion object {
            private val USERNAME = Regex("^[a-z0-9_]{3,32}$")
            const val MAX_COUNT = 50

            /** `null` if a name breaks the username rule, a joined notice names a subject, or the count is out of range. */
            fun of(
                event: MembershipEvent,
                subject: String,
                by: String?,
                count: Int = 0,
            ): Membership? {
                val joined = event == MembershipEvent.JOINED
                // JOINED and CHANGED name nobody; CHANGED is the fixed-text notice for a change whose names could not be shown.
                val nameless = joined || event == MembershipEvent.CHANGED
                val subjectOk = if (nameless) subject.isEmpty() else USERNAME.matches(subject)
                val countOk = if (joined) count in 1..MAX_COUNT else count == 0
                return Membership(event, subject, by, count).takeIf { subjectOk && countOk && (by == null || USERNAME.matches(by)) }
            }
        }
    }

    /** "Message unavailable": malformed, too big or a failed check. */
    data object Unavailable : MessageBody

    /** "Update the app": a newer content version or an unknown type. */
    data object UpdateNeeded : MessageBody
}

/** What a [MessageBody.Membership] notice says happened. */
enum class MembershipEvent { ADDED, REMOVED, JOINED, CHANGED }

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
 * order number, a label for display order only, `null` while one of ours is not accepted yet. [at] is local: the
 * server's envelope carries no time, so the time shown is when this device sent or first received the message.
 */
@Suppress("LongParameterList") // One record; grouping its fields would only rename them.
class ChatMessage(
    val groupId: KeyBytes,
    val reference: KeyBytes,
    val sender: String?,
    val seq: Long?,
    val body: MessageBody,
    val state: DeliveryState?,
    /** When this device first saw or sent the message, seconds since the epoch by its own clock (the server sends no time). */
    val at: Long,
) {
    override fun toString(): String = "ChatMessage(state=$state)"
}

/**
 * A group name that counts (#217): received, or sent and accepted by the server. One that is still sending or failed does not,
 * and retention never lets it stand in for the settled one (the same test in `settledName` and in eviction).
 */
fun ChatMessage.isSettledName(): Boolean = body is MessageBody.GroupMeta && (state == null || state == DeliveryState.SENT)
