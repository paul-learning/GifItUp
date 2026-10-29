package app.gifitup.shared.domain.chat

import app.gifitup.shared.domain.contacts.KeyBytes

/**
 * Where the newest message the user has seen is kept per group (ADR-0017: a sealed record in the group's folder).
 * Both calls may fail with the store's own closed exception; [ReadMarks] treats that as "unread again", never as a
 * reason to block the screen.
 */
interface ReadMarkStore {
    /** The reference of the newest message of [group] that was shown, or `null` if none is known. */
    suspend fun newestSeen(group: KeyBytes): KeyBytes?

    /** Remembers [reference] (a message the store holds) as the newest one shown. A group the store doesn't hold is ignored. */
    suspend fun markSeen(
        group: KeyBytes,
        reference: KeyBytes,
    )
}
