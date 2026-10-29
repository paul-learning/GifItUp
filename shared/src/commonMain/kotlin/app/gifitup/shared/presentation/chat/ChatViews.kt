package app.gifitup.shared.presentation.chat

import app.gifitup.shared.data.network.ApiError
import app.gifitup.shared.domain.auth.AuthError
import app.gifitup.shared.domain.contacts.KeyBytes
import app.gifitup.shared.domain.groups.GroupInfo
import app.gifitup.shared.domain.groups.GroupStatus
import app.gifitup.shared.domain.messages.ChatMessage
import app.gifitup.shared.domain.messages.MessageBody

/** What a conversation is called: the group's name (a `group_meta` message, 3 or more people), else the other members. */
sealed interface ConversationTitle {
    class Named(
        val name: String,
    ) : ConversationTitle

    /** The other members' usernames; empty only for a group whose members are not known (removed). */
    class Members(
        val names: List<String>,
    ) : ConversationTitle
}

/** Whether the title names several people: a group, where messages show who sent them. */
val ConversationTitle.isGroup: Boolean
    get() =
        when (this) {
            is ConversationTitle.Named -> true
            is ConversationTitle.Members -> names.size >= 2
        }

/** The name whose first letter an avatar shows: the group's name, else the first other member (empty if there is none). */
val ConversationTitle.avatarName: String
    get() =
        when (this) {
            is ConversationTitle.Named -> name
            is ConversationTitle.Members -> names.firstOrNull().orEmpty()
        }

/** A stable text key for a list row or message: the identity's bytes in hex. */
fun KeyBytes.listKey(): String = bytes.joinToString("") { it.toUByte().toString(HEX_RADIX).padStart(2, '0') }

private const val HEX_RADIX = 16

/** The last message of a conversation as a line of the list. */
sealed interface Preview {
    data object Nothing : Preview

    class Gif(
        val caption: String?,
        val mine: Boolean,
    ) : Preview

    class Renamed(
        val name: String,
    ) : Preview

    data object Unavailable : Preview

    data object UpdateNeeded : Preview
}

/** Why a conversation can't be used right now, in the user's terms (ADR-0016). */
enum class ConversationProblem {
    /** Missing messages, a broken commit or a mismatching key: it has to be joined again. */
    STOPPED,

    /** This device's state is older than something the server accepted: read-only until it is joined again. */
    READ_ONLY,

    /** The group is gone for this device (removed, or deleted). */
    GONE,
}

/** Why a refresh or an action did not work. */
enum class ChatProblem { OFFLINE, NOT_SIGNED_IN, FAILED }

internal fun AuthError.toChatProblem(): ChatProblem =
    when (this) {
        is AuthError.Api -> {
            when (error) {
                ApiError.NETWORK -> ChatProblem.OFFLINE
                ApiError.UNAUTHORIZED -> ChatProblem.NOT_SIGNED_IN
                else -> ChatProblem.FAILED
            }
        }

        is AuthError.Local -> {
            if (this == AuthError.Local.NOT_REGISTERED) ChatProblem.NOT_SIGNED_IN else ChatProblem.FAILED
        }
    }

internal fun GroupStatus.toProblem(): ConversationProblem? =
    when (this) {
        GroupStatus.ACTIVE -> null
        GroupStatus.READ_ONLY_ROLLBACK -> ConversationProblem.READ_ONLY
        GroupStatus.REMOVED -> ConversationProblem.GONE
        else -> ConversationProblem.STOPPED
    }

internal fun titleOf(
    info: GroupInfo,
    messages: List<ChatMessage>,
): ConversationTitle {
    // A rename counts only in a group of three or more: a 2-member chat shows the contact's name (ADR-0006 B3).
    val name = if (info.others.size >= 2) messages.lastOrNull { it.body is MessageBody.GroupMeta }?.body else null
    return if (name is MessageBody.GroupMeta) ConversationTitle.Named(name.name) else ConversationTitle.Members(info.others)
}

internal fun previewOf(last: ChatMessage?): Preview =
    when (val body = last?.body) {
        null -> Preview.Nothing
        is MessageBody.Gif -> Preview.Gif(body.caption, mine = last.sender == null)
        is MessageBody.GroupMeta -> Preview.Renamed(body.name)
        MessageBody.Unavailable -> Preview.Unavailable
        MessageBody.UpdateNeeded -> Preview.UpdateNeeded
    }
