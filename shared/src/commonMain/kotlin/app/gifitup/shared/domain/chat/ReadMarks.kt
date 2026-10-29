package app.gifitup.shared.domain.chat

import app.gifitup.shared.domain.contacts.KeyBytes
import app.gifitup.shared.domain.messages.ChatMessage
import app.gifitup.shared.domain.messages.HistoryStoreException

/**
 * Which messages the user has seen: the ones up to the newest that was on screen when the conversation was last shown.
 * A received message is unread until a screen has shown it. By position in the group's arrival order and not by time, so
 * a message that arrives in the same second as the last view still counts as unread. If the newest seen message is
 * gone (history retention removes the oldest first) or was never known, everything held is unread.
 *
 * The mark is kept in [persisted] (a sealed record, ADR-0017) so it survives a restart. A store that fails only costs
 * the mark: the value in memory still holds for this session, and after a restart the messages count as unread again.
 */
class ReadMarks(
    private val persisted: ReadMarkStore? = null,
) {
    private val newest = mutableMapOf<KeyBytes, KeyBytes?>()

    /** What the last marked list of each group held: an older list (a slower screen) can't move the mark back. */
    private val shown = mutableMapOf<KeyBytes, Set<KeyBytes>>()

    /** The user is looking at [messages] of [group] (in arrival order). */
    suspend fun markRead(
        group: KeyBytes,
        messages: List<ChatMessage>,
    ) {
        val latest = messages.lastOrNull()?.reference ?: return
        if (current(group) == latest || latest in shown[group].orEmpty()) return
        shown[group] = messages.mapTo(mutableSetOf()) { it.reference }
        newest[group] = latest
        try {
            persisted?.markSeen(group, latest)
        } catch (e: HistoryStoreException) {
            // Unread again after a restart; nothing else is lost. A cipher failure is not a storage problem: it goes on.
            if (e.kind != HistoryStoreException.Kind.STORAGE) throw e
        }
    }

    suspend fun unread(
        group: KeyBytes,
        messages: List<ChatMessage>,
    ): Int {
        val mark = current(group)
        val index = messages.indexOfFirst { it.reference == mark }
        val after = if (mark == null || index < 0) messages else messages.subList(index + 1, messages.size)
        return after.count { it.sender != null }
    }

    /** The mark of [group]: from memory, else from the store once (a failed read is tried again next time). */
    private suspend fun current(group: KeyBytes): KeyBytes? {
        if (group !in newest) {
            try {
                newest[group] = persisted?.newestSeen(group)
            } catch (e: HistoryStoreException) {
                // Counts as unread for now; the next call asks again.
                if (e.kind != HistoryStoreException.Kind.STORAGE) throw e
            }
        }
        return newest[group]
    }
}
