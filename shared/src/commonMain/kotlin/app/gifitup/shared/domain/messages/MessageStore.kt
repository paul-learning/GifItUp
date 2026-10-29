package app.gifitup.shared.domain.messages

import app.gifitup.shared.domain.contacts.KeyBytes

/**
 * The messages of the groups on this device (ADR-0006 B9). A message is identified by the MLS group id of the group that
 * processed it (never a group id from the server's envelope) and its reference; the first one seen wins. Messages are
 * plaintext (GIF links, captions), so a durable implementation has to encrypt them at rest: ADR-0011 keeps the
 * `LocalStore` free of anything that is not encrypted or public. [EncryptedMessageStore] is the durable one (ADR-0017);
 * [InMemoryMessageStore] is what the app still uses until the platform stores are wired in, and its history ends with the
 * process.
 */
interface MessageStore {
    /** Adds [message] unless that (group, reference) is known already; whether it was added. */
    suspend fun insert(message: ChatMessage): Boolean

    /** Sets the delivery state of one of our own messages; unknown or received messages are left alone. */
    suspend fun setState(
        groupId: KeyBytes,
        reference: KeyBytes,
        state: DeliveryState,
        seq: Long? = null,
    )

    /** The group's messages in the order they were added. */
    suspend fun messages(groupId: KeyBytes): List<ChatMessage>

    /** Deletes the group's messages (the group was forgotten on this device). */
    suspend fun forget(groupId: KeyBytes)
}
