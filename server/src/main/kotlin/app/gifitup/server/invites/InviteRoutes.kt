package app.gifitup.server.invites

import app.gifitup.protocol.ApiErrorCode
import app.gifitup.protocol.CreateInviteResponse
import app.gifitup.protocol.ErrorResponse
import app.gifitup.protocol.InviteInfo
import app.gifitup.protocol.InviteListResponse
import app.gifitup.server.auth.session
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

internal const val INVITES_PATH = "/invites"

/**
 * The invite endpoints (ADR-0010). Not in the public paths, so the session interceptor has already checked the caller;
 * the account is the session's. Blocking work runs on the IO dispatcher.
 */
internal fun Route.inviteRoutes(invites: InviteService) {
    post(INVITES_PATH) {
        val accountId = call.session.record.accountId
        when (val outcome = withContext(Dispatchers.IO) { invites.create(accountId) }) {
            is CreateOutcome.Created -> {
                call.noStore() // the link carries the code (#13 SEC-48)
                val invite = outcome.invite
                call.respond(HttpStatusCode.Created, CreateInviteResponse(invite.id.toString(), invite.link, invite.expiresAt.epochSecond))
            }

            CreateOutcome.LimitReached -> {
                call.respond(HttpStatusCode.Conflict, ErrorResponse(ApiErrorCode.INVITE_LIMIT))
            }

            CreateOutcome.RateLimited -> {
                call.respond(HttpStatusCode.TooManyRequests, ErrorResponse(ApiErrorCode.RATE_LIMITED))
            }

            CreateOutcome.NoAccount -> {
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse(ApiErrorCode.UNAUTHORIZED))
            }
        }
    }
    get(INVITES_PATH) {
        val listed = withContext(Dispatchers.IO) { invites.list(call.session.record.accountId) }
        call.noStore() // the caller's own invite tree; no shared cache should keep it
        call.respond(
            InviteListResponse(
                listed.map { InviteInfo(it.id.toString(), it.createdAt.epochSecond, it.expiresAt.epochSecond, it.status, it.usedBy) },
            ),
        )
    }
    delete("$INVITES_PATH/{id}") {
        val inviteId = parseCanonicalUuid(call.parameters["id"])
        if (inviteId == null) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(ApiErrorCode.BAD_REQUEST))
            return@delete
        }
        val revoked = withContext(Dispatchers.IO) { invites.revoke(call.session.record.accountId, inviteId) }
        // Someone else's, an admin invite, used, already revoked, expired or unknown: all one 404 (no probing).
        if (revoked) {
            call.respond(
                HttpStatusCode.NoContent,
            )
        } else {
            call.respond(HttpStatusCode.NotFound, ErrorResponse(ApiErrorCode.NOT_FOUND))
        }
    }
}

private fun ApplicationCall.noStore() = response.headers.append(HttpHeaders.CacheControl, "no-store")

private val CANONICAL_UUID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

/**
 * Only the lowercase canonical form the server itself sends. `UUID.fromString` alone is lenient (it accepts
 * `1-1-1-1-1`), so the shape is checked first.
 */
internal fun parseCanonicalUuid(text: String?): UUID? = text?.takeIf { CANONICAL_UUID.matches(it) }?.let(UUID::fromString)
