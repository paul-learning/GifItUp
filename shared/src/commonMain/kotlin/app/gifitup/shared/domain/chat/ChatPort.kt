package app.gifitup.shared.domain.chat

import app.gifitup.shared.data.content.GifReference
import app.gifitup.shared.data.network.Session
import app.gifitup.shared.domain.auth.AuthResult
import app.gifitup.shared.domain.contacts.KeyBytes
import app.gifitup.shared.domain.groups.GroupCreation
import app.gifitup.shared.domain.groups.GroupList
import app.gifitup.shared.domain.groups.SyncOutcome
import app.gifitup.shared.domain.groups.WelcomesReport

/**
 * What the chat screens' logic needs from the group engine (ADR-0016), with the message store already behind it: every
 * call that reads the Delivery Service feeds what it decrypts to the [app.gifitup.shared.domain.messages.MessageStore].
 * [ChatService] is the app's implementation; tests use a fake.
 */
interface ChatPort {
    suspend fun groups(session: Session): AuthResult<GroupList>

    /** Joins the groups the user was added to. */
    suspend fun welcomes(session: Session): AuthResult<WelcomesReport>

    suspend fun sync(
        session: Session,
        groupId: KeyBytes,
    ): AuthResult<SyncOutcome>

    suspend fun create(
        session: Session,
        target: String,
    ): AuthResult<GroupCreation>

    /**
     * Adds [target] to the group. Only a **group** may grow (#217); [initial] is for the creator's own setup of a group it
     * just made (the flow adds the friends it chose after the first one, while the chat still has one other member).
     */
    suspend fun add(
        session: Session,
        groupId: KeyBytes,
        target: String,
        initial: Boolean = false,
    ): AuthResult<SyncOutcome>

    suspend fun sendGif(
        session: Session,
        groupId: KeyBytes,
        reference: GifReference,
        caption: String?,
    ): AuthResult<SyncOutcome>

    /** Names the group (a `group_meta` message, #217); it counts as the chat's name only once the server accepted it. */
    suspend fun rename(
        session: Session,
        groupId: KeyBytes,
        name: String,
    ): AuthResult<SyncOutcome>

    /** Posts the group's unsettled message again. */
    suspend fun resume(
        session: Session,
        groupId: KeyBytes,
    ): AuthResult<SyncOutcome>
}
