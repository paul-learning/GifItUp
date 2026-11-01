package app.gifitup.shared.domain.chat

import app.gifitup.shared.domain.messages.ChatMessage
import app.gifitup.shared.domain.messages.MessageBody
import app.gifitup.shared.domain.messages.isSettledName

/**
 * Whether a chat is direct or a group, as this device sees it (#217; a best-effort local view, not agreed state; the history
 * is a cache). A group has a name or two or more other members; a direct chat exactly one other member and no name; a chat with
 * one other member and **any unreadable record** is [UNKNOWN] (the name may be in it), and so is one whose members are not
 * known and that has no name. "Nobody can be added to a direct chat" is a client policy, never a guarantee.
 */
enum class ChatKind { DIRECT, GROUP, UNKNOWN }

fun chatKind(
    others: Int,
    named: Boolean,
    unreadable: Int,
): ChatKind =
    when {
        named || others >= 2 -> ChatKind.GROUP
        others == 1 && unreadable == 0 -> ChatKind.DIRECT
        else -> ChatKind.UNKNOWN
    }

/**
 * The newest group name that is **settled**: received, or sent and accepted. A name that is still sending or failed never
 * counts, so a failed rename can't make a chat look like a group (#217).
 */
fun settledName(messages: List<ChatMessage>): String? = (messages.lastOrNull { it.isSettledName() }?.body as? MessageBody.GroupMeta)?.name
