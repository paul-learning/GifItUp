package app.gifitup.shared.domain.chat

import app.gifitup.shared.domain.contacts.KeyBytes
import app.gifitup.shared.domain.messages.ChatMessage

/**
 * Which messages the user has seen, by the time they opened the conversation (in memory, like the history it belongs
 * to; both go with the durable store, #177). A received message is unread if this device first saw it after the
 * conversation was last open.
 */
class ReadMarks {
    private val seenUntil = mutableMapOf<KeyBytes, Long>()

    fun markRead(
        group: KeyBytes,
        at: Long,
    ) {
        seenUntil[group] = maxOf(seenUntil[group] ?: 0L, at)
    }

    fun unread(
        group: KeyBytes,
        messages: List<ChatMessage>,
    ): Int {
        val mark = seenUntil[group] ?: 0L
        return messages.count { it.sender != null && it.at > mark }
    }
}
