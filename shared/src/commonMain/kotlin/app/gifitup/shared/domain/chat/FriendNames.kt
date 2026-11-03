package app.gifitup.shared.domain.chat

import app.gifitup.shared.data.network.Session
import app.gifitup.shared.domain.auth.AuthResult
import app.gifitup.shared.domain.contacts.KeyBytes

/** The usernames the user can start a chat with (see `DeviceAuth.friendNames`); `null` if the contact book can't be read. */
interface FriendNames {
    suspend fun names(): List<String>?

    /**
     * The friends this device saved a key for (every status but a merely listed one, so a friend whose key changed still
     * counts as saved) with those keys, by username, or `null` if the contact book can't be read. Used to tell a member
     * who is a saved friend from one who only has a friend's name (#31).
     */
    suspend fun pinned(): Map<String, Set<KeyBytes>>? = emptyMap()

    /** Reads the server's member list into the contact book, so the people the user was invited by or invited show up. */
    suspend fun refresh(session: Session): AuthResult<Unit>
}
