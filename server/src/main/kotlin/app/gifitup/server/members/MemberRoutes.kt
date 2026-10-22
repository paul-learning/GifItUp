package app.gifitup.server.members

import app.gifitup.protocol.MemberDevice
import app.gifitup.protocol.MemberInfo
import app.gifitup.protocol.MembersResponse
import app.gifitup.server.auth.Base64Url
import app.gifitup.server.auth.session
import io.ktor.http.HttpHeaders
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal const val MEMBERS_PATH = "/members"

/**
 * `GET /members` (ADR-0014). Not public, so the session interceptor has already checked the caller; the account is the
 * session's and the request carries nothing else. The list names the caller's invite tree edges, so no shared cache
 * may keep it.
 */
internal fun Route.memberRoutes(members: MemberService) {
    get(MEMBERS_PATH) {
        val listed = withContext(Dispatchers.IO) { members.list(call.session.record.accountId) }
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        call.respond(
            MembersResponse(
                listed.members.map { m ->
                    MemberInfo(
                        m.accountId.toString(),
                        m.username,
                        m.relation,
                        m.devices.map { MemberDevice(it.deviceId.toString(), Base64Url.encode(it.signatureKey)) },
                    )
                },
                listed.more,
            ),
        )
    }
}
