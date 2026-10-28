package app.gifitup.ui

import app.gifitup.shared.presentation.chat.Ago
import app.gifitup.shared.presentation.chat.ChatProblem
import app.gifitup.shared.presentation.chat.ConversationProblem
import app.gifitup.shared.presentation.chat.ConversationTitle
import app.gifitup.shared.presentation.chat.NewChatProblem
import app.gifitup.shared.presentation.chat.Preview

/**
 * The fixed words of the chat screens (#25). Text a friend wrote (a caption, a group name) is wrapped in Unicode
 * isolates, so its direction can't reorder the words around it (ADR-0006 B3a); it is plain text, never a link.
 */
internal object ChatTexts {
    const val TITLE = "Chats"
    const val NEW_CHAT = "New chat"
    const val NO_CHATS = "No chats yet. Start one with a friend."
    const val NO_MESSAGES = "No messages yet"
    const val REFRESHING = "Looking for new messages…"
    const val BACK = "Back"
    const val START_CHAT = "Start chat"
    const val NEW_CHAT_HINT = "Choose one friend for a chat, or several for a group."
    const val NO_FRIENDS = "You have no saved friends yet. Invite someone, or open the Friends screen."
    const val CREATED = "Chat started."

    /** The user's text or a name from the other side, isolated from the words around it. */
    fun isolated(text: String): String = "⁨$text⁩"

    fun title(title: ConversationTitle): String =
        when (title) {
            is ConversationTitle.Named -> isolated(title.name)
            is ConversationTitle.Members -> if (title.names.isEmpty()) "Unknown chat" else title.names.joinToString(", ") { isolated(it) }
        }

    fun preview(preview: Preview): String =
        when (preview) {
            Preview.Nothing -> NO_MESSAGES
            is Preview.Gif -> (if (preview.mine) "You: " else "") + (preview.caption?.let { "GIF · ${isolated(it)}" } ?: "GIF")
            is Preview.Renamed -> "The chat was renamed to ${isolated(preview.name)}"
            Preview.Unavailable -> "Message unavailable"
            Preview.UpdateNeeded -> "Update the app to see this message"
        }

    fun ago(ago: Ago): String =
        when (ago) {
            Ago.JustNow -> "now"
            is Ago.Minutes -> "${ago.count} min"
            is Ago.Hours -> "${ago.count} h"
            is Ago.Days -> "${ago.count} d"
        }

    fun problem(problem: ConversationProblem): String =
        when (problem) {
            ConversationProblem.STOPPED -> "Stopped: messages are missing or a change did not fit. This chat has to be started again."
            ConversationProblem.READ_ONLY -> "Read-only: this device is behind what the server holds."
            ConversationProblem.GONE -> "You are no longer in this chat."
        }

    fun listProblem(problem: ChatProblem): String =
        when (problem) {
            ChatProblem.OFFLINE -> "No answer from the server. This is what the app has."
            ChatProblem.NOT_SIGNED_IN -> "The server didn't accept this device."
            ChatProblem.FAILED -> "Some messages could not be read. Trying again later may help."
        }

    fun newChatProblem(problem: NewChatProblem): String =
        when (problem) {
            NewChatProblem.FRIENDS_UNAVAILABLE -> "This device's friends list can't be used."
            NewChatProblem.NOBODY -> "Choose at least one friend."
            NewChatProblem.CANT_REACH -> "That friend can't be reached right now. They may need to open the app once."
            NewChatProblem.UNFINISHED -> "The chat was made but is not confirmed yet. It will be finished when you open it."
            NewChatProblem.PARTLY -> "The chat was made, but not everyone could be added."
            NewChatProblem.OFFLINE -> "No answer from the server."
            NewChatProblem.NOT_SIGNED_IN -> "The server didn't accept this device."
            NewChatProblem.FAILED -> "That did not work."
        }

    fun unread(count: Int): String = if (count > MAX_BADGE) "$MAX_BADGE+" else count.toString()

    private const val MAX_BADGE = 99

    fun rowDescription(
        title: String,
        preview: String,
        ago: String?,
        unread: Int,
    ): String =
        buildString {
            append(title).append(". ").append(preview)
            if (ago != null) append(". ").append(ago)
            if (unread > 0) append(". ").append(unread).append(if (unread == 1) " unread message" else " unread messages")
        }
}
