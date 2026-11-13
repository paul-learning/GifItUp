package app.gifitup.shared.domain.diagnostics

/**
 * What the diagnostics log can say (#154 follow-up, the owner's request of 2026-11-13). A **closed** set on purpose: the log
 * takes an event, never a string, so no name, id, host, key, token, invite code, ciphertext, GIF or search query can end up in
 * it, and a log the user shares tells the owner only which of these happened and when. Failures and the app's own life cycle
 * only: no event for a message sent or received, a chat request, a contact or a group, because the moment of such an event would
 * say who the user talks to and when (plan review SEC-2, SEC-4). A new code needs a reviewer to check exactly that. [code] is what is stored: it never
 * changes for an event and is never reused, so an old log stays readable; [label] is what the report prints.
 */
enum class DiagEvent(
    val code: Int,
    val label: String,
) {
    APP_START(1, "app start"),
    SOCKET_CONNECTED(2, "live updates connected"),
    SOCKET_LOST(3, "live updates lost"),
    SYNC_FAILED_NETWORK(4, "refresh failed: network"),
    SYNC_FAILED_SERVER(5, "refresh failed: server"),
    SYNC_FAILED_STORAGE(6, "refresh failed: device storage"),
    HISTORY_PROBLEM(7, "history problem"),
    SEND_FAILED(8, "send failed"),
    WELCOME_STALE_DROPPED(9, "stale invitation to a chat dropped"),
    LOGIN_FAILED(12, "sign-in failed"),
    STORAGE_FAILED(13, "device storage failed"),
    LOG_CLEARED(14, "log cleared"),
    ;

    companion object {
        private val byCode = entries.associateBy { it.code }

        /** The event stored under [code], or `null` for a code this build doesn't know (a newer build wrote it). */
        fun ofCode(code: Int): DiagEvent? = byCode[code]
    }
}

/** One line of the log: the event and the second (UTC, seconds since 1970) it happened in. */
data class DiagEntry(
    val at: Long,
    val event: DiagEvent,
)
