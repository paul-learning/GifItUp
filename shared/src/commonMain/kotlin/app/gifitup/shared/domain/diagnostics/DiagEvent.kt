@file:Suppress("MagicNumber") // The stored codes are the content of this file.

package app.gifitup.shared.domain.diagnostics

/**
 * What the diagnostics log can say (#154 follow-up, the owner's request of 2026-11-13). A **closed** set on purpose: the log
 * takes an event, never a string, so no name, id, host, key, token, invite code, ciphertext, GIF or search query can end up in
 * it, and a log the user shares tells the owner only which of these happened and when. Failures and the app's own life cycle
 * only: no event for a message sent or received, a chat request, a contact or a group, because the moment of such an event
 * would say who the user talks to and when (plan review SEC-2, SEC-4). A new code needs a reviewer to check exactly that.
 * [code] is what is stored: it never changes for an event and is never reused, so an old log stays readable; [label] is
 * what the report prints.
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
    HISTORY_PROBLEM(7, "history problem"),
    SEND_FAILED(8, "send failed"),
    LOGIN_FAILED(12, "sign-in failed"),
    LOG_CLEARED(14, "log cleared"),

    // The debug detail (a [DiagDetail] says why). Failures and states of the system only, never a contact's or a chat's.
    CALL_FAILED_READ(15, "read failed"),
    CALL_FAILED_SEND(16, "send failed at the server or crypto"),
    CALL_FAILED_SETUP(17, "chat setup failed"),
    SYNC_NOTE(18, "sync note"),
    WELCOME_REJECTED(19, "invitation rejected"),
    SOCKET_STATE(20, "live updates state"),
    SOCKET_STOPPED(21, "live updates stopped"),
    MEDIA_FAILED(22, "gif could not be shown"),
    ;

    companion object {
        private val byCode = entries.associateBy { it.code }

        /** The event stored under [code], or `null` for a code this build doesn't know (a newer build wrote it). */
        fun ofCode(code: Int): DiagEvent? = byCode[code]
    }
}

/**
 * One line of the log: the event, the minute (UTC, seconds since 1970) it happened in, the closed [detail] that says why (if
 * the event has one) and how many times it happened in that minute ([count], at most [MAX_COUNT]).
 */
data class DiagEntry(
    val at: Long,
    val event: DiagEvent,
    val detail: DiagDetail? = null,
    val count: Int = 1,
) {
    companion object {
        const val MAX_COUNT = 255
    }
}
