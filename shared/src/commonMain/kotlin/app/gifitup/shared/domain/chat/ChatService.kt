package app.gifitup.shared.domain.chat

import app.gifitup.shared.data.content.GifReference
import app.gifitup.shared.data.network.Session
import app.gifitup.shared.domain.auth.AuthResult
import app.gifitup.shared.domain.contacts.KeyBytes
import app.gifitup.shared.domain.groups.ChatActions
import app.gifitup.shared.domain.groups.GroupCreation
import app.gifitup.shared.domain.groups.GroupList
import app.gifitup.shared.domain.groups.SyncOutcome
import app.gifitup.shared.domain.groups.WelcomesReport
import app.gifitup.shared.domain.messages.MessageIntake
import app.gifitup.shared.domain.messages.MessageStore
import app.gifitup.shared.domain.messages.SendGif

/**
 * [ChatPort] over [ChatActions]: everything it reads goes into [store] through a [MessageIntake], and a GIF is sent
 * with [SendGif], so the store shows what is sending, sent or failed. One instance per signed-in app instance.
 */
class ChatService(
    private val actions: ChatActions,
    store: MessageStore,
    nowEpochSeconds: () -> Long,
) : ChatPort {
    private val intake = MessageIntake(store, nowEpochSeconds)
    private val gifs = SendGif(actions, store, nowEpochSeconds)

    override suspend fun groups(session: Session): AuthResult<GroupList> = actions.groups(session, intake)

    override suspend fun welcomes(session: Session): AuthResult<WelcomesReport> = actions.welcomes(session, intake)

    override suspend fun sync(
        session: Session,
        groupId: KeyBytes,
    ): AuthResult<SyncOutcome> = actions.sync(session, groupId.bytes, intake)

    override suspend fun create(
        session: Session,
        target: String,
    ): AuthResult<GroupCreation> = actions.create(session, target, intake)

    override suspend fun add(
        session: Session,
        groupId: KeyBytes,
        target: String,
    ): AuthResult<SyncOutcome> = actions.add(session, groupId.bytes, target, intake)

    override suspend fun sendGif(
        session: Session,
        groupId: KeyBytes,
        reference: GifReference,
        caption: String?,
    ): AuthResult<SyncOutcome> = gifs(session, groupId.bytes, reference, caption, intake)

    override suspend fun resume(
        session: Session,
        groupId: KeyBytes,
    ): AuthResult<SyncOutcome> = gifs.resume(session, groupId.bytes, intake)
}
