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
import app.gifitup.shared.domain.messages.HistoryAccess
import app.gifitup.shared.domain.messages.MessageIntake
import app.gifitup.shared.domain.messages.SendGif

/**
 * [ChatPort] over [ChatActions]: everything it reads goes into [store] through a [MessageIntake], and a GIF is sent
 * with [SendGif], so the store shows what is sending, sent or failed. One instance per signed-in app instance.
 */
class ChatService(
    private val actions: ChatActions,
    private val store: HistoryAccess,
    nowEpochSeconds: () -> Long,
) : ChatPort {
    private val intake = MessageIntake(store, nowEpochSeconds)
    private val gifs = SendGif(actions, store, nowEpochSeconds)
    private val policy = AddPolicy(store)

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

    /**
     * Adds a friend to a **group** only (#217): a direct chat, and a chat whose kind this device can't tell (members not
     * known, a record that can't be read, the history closed), refuse with [SyncOutcome.InvalidRequest] and never reach the
     * engine. A client policy, not cryptography: a modified client could still add someone (members see the change). This
     * is the only way the app adds members; the dev CLI's `chat-add` is a test driver and has no such rule.
     */
    override suspend fun add(
        session: Session,
        groupId: KeyBytes,
        target: String,
        initial: Boolean,
    ): AuthResult<SyncOutcome> =
        if (initial) {
            actions.add(session, groupId.bytes, target, intake)
        } else {
            addToGroup(session, groupId, target)
        }

    private suspend fun addToGroup(
        session: Session,
        groupId: KeyBytes,
        target: String,
    ): AuthResult<SyncOutcome> =
        when (val allowed = mayAdd(session, groupId)) {
            is AuthResult.Failed -> {
                allowed
            }

            is AuthResult.Ok -> {
                if (allowed.value) {
                    actions.add(
                        session,
                        groupId.bytes,
                        target,
                        intake,
                    )
                } else {
                    AuthResult.Ok(SyncOutcome.InvalidRequest)
                }
            }
        }

    private suspend fun mayAdd(
        session: Session,
        groupId: KeyBytes,
    ): AuthResult<Boolean> =
        when (val listed = actions.groups(session, intake)) {
            is AuthResult.Failed -> {
                listed
            }

            is AuthResult.Ok -> {
                AuthResult.Ok(
                    listed.value.groups
                        .firstOrNull { it.id == groupId }
                        ?.let { policy.allows(it) } == true,
                )
            }
        }

    override suspend fun rename(
        session: Session,
        groupId: KeyBytes,
        name: String,
    ): AuthResult<SyncOutcome> = gifs.rename(session, groupId.bytes, name, intake)

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
