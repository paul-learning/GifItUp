package app.gifitup.shared.domain.chat

import app.gifitup.shared.data.network.Session
import app.gifitup.shared.domain.auth.AuthResult

/** The usernames the user can start a chat with (see `DeviceAuth.friendNames`); `null` if the contact book can't be read. */
interface FriendNames {
    suspend fun names(): List<String>?

    /** Reads the server's member list into the contact book, so the people the user was invited by or invited show up. */
    suspend fun refresh(session: Session): AuthResult<Unit>
}
