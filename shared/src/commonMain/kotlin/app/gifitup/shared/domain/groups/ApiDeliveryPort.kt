package app.gifitup.shared.domain.groups

import app.gifitup.shared.data.network.ApiError
import app.gifitup.shared.data.network.ApiResult
import app.gifitup.shared.data.network.DeliveryEnvelope
import app.gifitup.shared.data.network.GifItUpApi
import app.gifitup.shared.data.network.GroupState
import app.gifitup.shared.data.network.MessagePage
import app.gifitup.shared.data.network.PendingWelcomeEntry
import app.gifitup.shared.data.network.PostedMessage
import app.gifitup.shared.domain.auth.AuthError
import app.gifitup.shared.domain.auth.AuthResult
import app.gifitup.shared.domain.session.SessionHolder

/**
 * [DeliveryPort] over the real API and the logged-in [SessionHolder]. A session that can't be had (not registered,
 * wrong origin, login refused) is `UNAUTHORIZED` here: the engine only needs to know that the Delivery Service can't be
 * used right now, and the caller's own login path says why.
 */
class ApiDeliveryPort(
    private val api: GifItUpApi,
    private val sessions: SessionHolder,
) : DeliveryPort {
    override suspend fun createGroup(
        groupId: ByteArray,
        commit: DeliveryEnvelope,
    ): ApiResult<PostedMessage> = call { api.createGroup(it, groupId, commit) }

    override suspend fun post(
        groupId: ByteArray,
        envelope: DeliveryEnvelope,
    ): ApiResult<PostedMessage> = call { api.postMessage(it, groupId, envelope) }

    override suspend fun fetch(
        groupId: ByteArray,
        after: Long,
    ): ApiResult<MessagePage> = call { api.fetchMessages(it, groupId, after) }

    override suspend fun status(groupId: ByteArray): ApiResult<GroupState> = call { api.groupStatus(it, groupId) }

    override suspend fun ack(
        groupId: ByteArray,
        seq: Long,
    ): ApiResult<Unit> = call { api.ackMessages(it, groupId, seq) }

    override suspend fun leave(groupId: ByteArray): ApiResult<Unit> = call { api.leaveGroup(it, groupId) }

    override suspend fun pendingWelcomes(): ApiResult<List<PendingWelcomeEntry>> = call { api.pendingWelcomes(it) }

    override suspend fun ackWelcome(welcomeId: String): ApiResult<Unit> = call { api.ackWelcome(it, welcomeId) }

    private suspend fun <T> call(block: suspend (app.gifitup.shared.data.network.Session) -> ApiResult<T>): ApiResult<T> =
        when (val result = sessions.withSession(block)) {
            is AuthResult.Ok -> ApiResult.Ok(result.value)
            is AuthResult.Failed -> ApiResult.Failed((result.error as? AuthError.Api)?.error ?: ApiError.UNAUTHORIZED)
        }
}
