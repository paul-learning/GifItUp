package app.gifitup.shared.domain.groups

import app.gifitup.shared.data.network.ApiResult
import app.gifitup.shared.data.network.DeliveryEnvelope
import app.gifitup.shared.data.network.GifItUpApi
import app.gifitup.shared.data.network.GroupState
import app.gifitup.shared.data.network.MessagePage
import app.gifitup.shared.data.network.PendingWelcomeEntry
import app.gifitup.shared.data.network.PostedMessage
import app.gifitup.shared.data.network.Session

/** [DeliveryPort] over one logged-in [Session], for a caller that logs in once per run (the dev CLI). */
class SessionDeliveryPort(
    private val api: GifItUpApi,
    private val session: Session,
) : DeliveryPort {
    override suspend fun createGroup(
        groupId: ByteArray,
        commit: DeliveryEnvelope,
    ): ApiResult<PostedMessage> = api.createGroup(session, groupId, commit)

    override suspend fun post(
        groupId: ByteArray,
        envelope: DeliveryEnvelope,
    ): ApiResult<PostedMessage> = api.postMessage(session, groupId, envelope)

    override suspend fun fetch(
        groupId: ByteArray,
        after: Long,
    ): ApiResult<MessagePage> = api.fetchMessages(session, groupId, after)

    override suspend fun status(groupId: ByteArray): ApiResult<GroupState> = api.groupStatus(session, groupId)

    override suspend fun ack(
        groupId: ByteArray,
        seq: Long,
    ): ApiResult<Unit> = api.ackMessages(session, groupId, seq)

    override suspend fun leave(groupId: ByteArray): ApiResult<Unit> = api.leaveGroup(session, groupId)

    override suspend fun pendingWelcomes(): ApiResult<List<PendingWelcomeEntry>> = api.pendingWelcomes(session)

    override suspend fun ackWelcome(welcomeId: String): ApiResult<Unit> = api.ackWelcome(session, welcomeId)
}
