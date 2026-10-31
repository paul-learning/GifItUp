package app.gifitup.shared.domain.messages

import app.gifitup.shared.domain.contacts.KeyBytes

/**
 * What the screens need from the history beyond reading and writing messages (ADR-0017): a "try again" after a failure
 * that is waiting for the user, and how many stored records of a group could not be read. [LazyHistory] is the durable
 * one; [InMemoryMessageStore] has nothing to retry and nothing unreadable.
 */
interface HistoryAccess : MessageStore {
    /** The user's "try again": forgets a sticky failure and the cool-down; the next call opens the history again. */
    suspend fun retry()

    /** How many records of the group exist but could not be opened or read (they are kept, never shown). */
    suspend fun unreadable(groupId: KeyBytes): Int
}
