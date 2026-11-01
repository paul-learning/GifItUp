package app.gifitup.shared.domain.chat

import app.gifitup.shared.domain.groups.GroupInfo
import app.gifitup.shared.domain.messages.HistoryAccess
import app.gifitup.shared.domain.messages.HistoryStoreException

/**
 * Who may grow (#217, owner decision 2026-11-01: "it shouldnt be possible to add someone to a 1:1"): only a **group**. A
 * direct chat, and a chat whose kind this device can't tell (members not known, a record that can't be read, the history
 * closed or failing), is refused. A client policy over a best-effort local view, never a guarantee: a modified client could
 * still add someone, and the members would see the change.
 */
class AddPolicy(
    private val store: HistoryAccess,
) {
    suspend fun allows(info: GroupInfo): Boolean =
        try {
            chatKind(info.others.size, settledName(store.messages(info.id)) != null, store.unreadable(info.id)) == ChatKind.GROUP
        } catch (_: HistoryStoreException) {
            false
        }
}
