package app.gifitup.shared.domain.chat

import app.gifitup.shared.domain.contacts.KeyBytes
import app.gifitup.shared.domain.messages.ChatMessage

/**
 * Which messages the user has seen: the ones that were on screen when the conversation was last shown (in memory, like
 * the history it belongs to; both go with the durable store, #177). A received message is unread until a screen has
 * shown it. By identity and not by time, so a message that arrives in the same second as the last view still counts as
 * unread. Only references of messages the store still holds are kept.
 */
class ReadMarks {
    private val seen = mutableMapOf<KeyBytes, Set<KeyBytes>>()

    /** The user is looking at [messages] of [group]. */
    fun markRead(
        group: KeyBytes,
        messages: List<ChatMessage>,
    ) {
        seen[group] = messages.map { it.reference }.toSet()
    }

    fun unread(
        group: KeyBytes,
        messages: List<ChatMessage>,
    ): Int {
        val shown = seen[group].orEmpty()
        return messages.count { it.sender != null && it.reference !in shown }
    }
}
