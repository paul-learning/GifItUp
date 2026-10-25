@file:Suppress("TooManyFunctions") // One small function per endpoint and per answer helper.

package app.gifitup.server.delivery

import app.gifitup.protocol.AckRequest
import app.gifitup.protocol.ApiErrorCode
import app.gifitup.protocol.CreateGroupRequest
import app.gifitup.protocol.DeliveryKinds
import app.gifitup.protocol.ErrorResponse
import app.gifitup.protocol.FetchMessagesResponse
import app.gifitup.protocol.GroupMessage
import app.gifitup.protocol.GroupStatusResponse
import app.gifitup.protocol.PendingWelcome
import app.gifitup.protocol.PendingWelcomesResponse
import app.gifitup.protocol.PostMessageRequest
import app.gifitup.protocol.PostMessageResponse
import app.gifitup.server.auth.Base64Url
import app.gifitup.server.auth.session
import app.gifitup.server.db.DeliveryRepository
import app.gifitup.server.db.StoredMessage
import app.gifitup.server.invites.parseCanonicalUuid
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal const val GROUPS_PATH = "/groups"
internal const val WELCOMES_PATH = "/welcomes"
private val POST_PATH = Regex("/groups/[^/]+/messages")

/**
 * A commit with ten welcomes of 16 KiB (21,846 characters each) and a 16 KiB payload, plus JSON framing, with margin.
 * Only the two post endpoints get it; every other path keeps the small default (ADR-0015).
 */
internal const val DELIVERY_BODY_LIMIT_BYTES = 256L * 1024

private const val DEFAULT_FETCH_LIMIT = 50
private const val MAX_FETCH_LIMIT = 100

/** The body limit for [call]: the post endpoints' (exact method and path), or [default]. */
internal fun deliveryBodyLimit(
    call: ApplicationCall,
    default: Long,
): Long {
    val path = call.request.path()
    val isPost = call.request.httpMethod == HttpMethod.Post
    return if (isPost && (path == GROUPS_PATH || POST_PATH.matches(path))) DELIVERY_BODY_LIMIT_BYTES else default
}

/**
 * The Delivery Service endpoints (ADR-0015). Not public, so the session interceptor has already checked the caller;
 * the device is the session's, never the request's. A malformed group id in a path is the same `404` as an unknown group.
 */
internal fun Route.deliveryRoutes(service: DeliveryService) {
    createRoute(service)
    postRoute(service)
    fetchRoute(service)
    statusRoute(service)
    cursorRoutes(service)
    welcomeRoutes(service)
}

private fun Route.createRoute(service: DeliveryService) {
    post(GROUPS_PATH) {
        val request = call.receive<CreateGroupRequest>()
        val groupId = Base64Url.decodeUpTo(request.groupId, DeliveryLimitsBytes.MAX_GROUP_ID)?.takeIf(::validGroupId)
        withEnvelope(request.commit) { envelope ->
            if (groupId == null) {
                call.refuse(Refusal.INVALID)
            } else {
                answer(withContext(Dispatchers.IO) { service.createGroup(device(), groupId, envelope) }) { call.respond(it.toResponse()) }
            }
        }
    }
}

private fun Route.postRoute(service: DeliveryService) {
    post("$GROUPS_PATH/{groupId}/messages") {
        val request = call.receive<PostMessageRequest>()
        val groupId = groupId()
        withEnvelope(request) { envelope ->
            if (groupId == null) {
                call.refuse(Refusal.NOT_FOUND)
            } else {
                answer(withContext(Dispatchers.IO) { service.post(device(), groupId, envelope) }) { call.respond(it.toResponse()) }
            }
        }
    }
}

private fun Route.fetchRoute(service: DeliveryService) {
    get("$GROUPS_PATH/{groupId}/messages") {
        val after = call.request.queryParameters["after"]?.let { it.toLongOrNull() } ?: 0L
        val limit = call.request.queryParameters["limit"]?.let { it.toIntOrNull() } ?: DEFAULT_FETCH_LIMIT
        val groupId = groupId()
        when {
            after < 0 || limit !in 1..MAX_FETCH_LIMIT -> {
                call.refuse(Refusal.INVALID)
            }

            groupId == null -> {
                call.refuse(Refusal.NOT_FOUND)
            }

            else -> {
                answer(withContext(Dispatchers.IO) { service.fetch(device(), groupId, after, limit) }) {
                    call.noStore()
                    call.respond(FetchMessagesResponse(it.oldestSeq, it.lastSeq, it.messages.map(::message)))
                }
            }
        }
    }
}

private fun Route.statusRoute(service: DeliveryService) {
    get("$GROUPS_PATH/{groupId}") {
        val groupId = groupId()
        if (groupId == null) {
            call.refuse(Refusal.NOT_FOUND)
        } else {
            answer(withContext(Dispatchers.IO) { service.status(device(), groupId) }) {
                call.noStore()
                call.respond(
                    GroupStatusResponse(
                        epoch = it.epoch,
                        oldestSeq = it.oldestSeq,
                        lastSeq = it.lastSeq,
                        ackedSeq = it.member.ackedSeq,
                        lastSentSeq = it.member.lastSentSeq,
                        lastClientMsgId = it.member.lastClientMsgId?.let(Base64Url::encode),
                    ),
                )
            }
        }
    }
}

private fun Route.cursorRoutes(service: DeliveryService) {
    post("$GROUPS_PATH/{groupId}/ack") {
        val request = call.receive<AckRequest>()
        val groupId = groupId()
        if (groupId == null) {
            call.refuse(Refusal.NOT_FOUND)
        } else {
            answer(withContext(Dispatchers.IO) { service.ack(device(), groupId, request.seq) }) { call.respond(HttpStatusCode.NoContent) }
        }
    }
    post("$GROUPS_PATH/{groupId}/leave") {
        val groupId = groupId()
        if (groupId == null) {
            call.refuse(Refusal.NOT_FOUND)
        } else {
            answer(withContext(Dispatchers.IO) { service.leave(device(), groupId) }) { call.respond(HttpStatusCode.NoContent) }
        }
    }
}

private fun Route.welcomeRoutes(service: DeliveryService) {
    get(WELCOMES_PATH) {
        answer(withContext(Dispatchers.IO) { service.welcomes(device()) }) { rows ->
            call.noStore()
            call.respond(
                PendingWelcomesResponse(
                    rows.map { PendingWelcome(it.id.toString(), Base64Url.encode(it.groupId), Base64Url.encode(it.welcome)) },
                ),
            )
        }
    }
    post("$WELCOMES_PATH/{id}/ack") {
        val id = parseCanonicalUuid(call.parameters["id"])
        if (id == null) {
            call.refuse(Refusal.NOT_FOUND)
        } else {
            answer(withContext(Dispatchers.IO) { service.ackWelcome(device(), id) }) { call.respond(HttpStatusCode.NoContent) }
        }
    }
}

private fun validGroupId(id: ByteArray): Boolean = id.size >= DeliveryLimitsBytes.MIN_GROUP_ID

private fun RoutingContext.device() = call.session.record.deviceId

private fun RoutingContext.groupId(): ByteArray? =
    call.parameters["groupId"]
        ?.let { Base64Url.decodeUpTo(it, DeliveryLimitsBytes.MAX_GROUP_ID) }
        ?.takeIf(::validGroupId)

/** Parses the body into an envelope, or answers `400` (or `413`) and stops. */
private suspend fun RoutingContext.withEnvelope(
    request: PostMessageRequest,
    block: suspend (Envelope) -> Unit,
) {
    when (val parsed = EnvelopeParser.parse(request)) {
        is EnvelopeParse.Ok -> block(parsed.envelope)
        EnvelopeParse.Invalid -> call.refuse(Refusal.INVALID)
        EnvelopeParse.TooLarge -> call.refuse(Refusal.TOO_LARGE)
    }
}

private suspend fun <T> RoutingContext.answer(
    result: Delivery<T>,
    ok: suspend (T) -> Unit,
) {
    when (result) {
        is Delivery.Ok -> ok(result.value)
        is Delivery.Refused -> call.refuse(result.reason)
    }
}

private suspend fun ApplicationCall.refuse(reason: Refusal) {
    val (status, code) =
        when (reason) {
            Refusal.INVALID -> HttpStatusCode.BadRequest to ApiErrorCode.ENVELOPE_INVALID
            Refusal.TOO_LARGE -> HttpStatusCode.PayloadTooLarge to ApiErrorCode.PAYLOAD_TOO_LARGE
            Refusal.NOT_FOUND -> HttpStatusCode.NotFound to ApiErrorCode.NOT_FOUND
            Refusal.EPOCH_CONFLICT -> HttpStatusCode.Conflict to ApiErrorCode.EPOCH_CONFLICT
            Refusal.ENVELOPE_CONFLICT -> HttpStatusCode.Conflict to ApiErrorCode.ENVELOPE_CONFLICT
            Refusal.GROUP_FULL -> HttpStatusCode.Conflict to ApiErrorCode.GROUP_FULL
            Refusal.WELCOME_LIMIT -> HttpStatusCode.Conflict to ApiErrorCode.WELCOME_LIMIT
            Refusal.GROUP_LIMIT -> HttpStatusCode.Conflict to ApiErrorCode.GROUP_LIMIT
            Refusal.RATE_LIMITED -> HttpStatusCode.TooManyRequests to ApiErrorCode.RATE_LIMITED
        }
    respond(status, ErrorResponse(code))
}

private fun Posted.toResponse() = PostMessageResponse(seq, epoch)

private fun message(stored: StoredMessage) =
    GroupMessage(
        seq = stored.seq,
        senderDeviceId = stored.sender?.toString(),
        kind = if (stored.kind == DeliveryRepository.COMMIT) DeliveryKinds.COMMIT else DeliveryKinds.APPLICATION,
        epoch = stored.epoch,
        added = stored.added.map(Any::toString),
        removed = stored.removed.map(Any::toString),
        payload = Base64Url.encode(stored.payload),
    )

private fun ApplicationCall.noStore() = response.headers.append(HttpHeaders.CacheControl, "no-store")
