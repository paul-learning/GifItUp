package app.gifitup.shared.domain.invite

import app.gifitup.shared.data.network.ApiError
import app.gifitup.shared.data.network.ApiResult
import app.gifitup.shared.data.network.FakeAuthServer
import app.gifitup.shared.data.network.FakeAuthServer.Companion.DEVICE_ID
import app.gifitup.shared.data.network.FakeAuthServer.Companion.error
import app.gifitup.shared.data.network.FakeAuthServer.Companion.json
import app.gifitup.shared.data.network.Invite
import app.gifitup.shared.data.network.InviteState
import app.gifitup.shared.data.network.Session
import app.gifitup.shared.domain.auth.AuthError
import app.gifitup.shared.domain.auth.AuthResult
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs

class InvitesTest {
    private val server = FakeAuthServer()
    private val api = server.api()
    private val code = "AAAAAAAAAAAAAAAAAAAAAQ"
    private val id = "11111111-2222-4333-8444-555555555555"

    private suspend fun session(): Session = assertIs<ApiResult.Ok<Session>>(api.login(DEVICE_ID, ByteArray(56), ByteArray(64))).value

    private fun created(link: String) = json("""{"inviteId":"$id","link":"$link","expiresAt":1700000000}""", HttpStatusCode.Created)

    @Test
    fun aLinkForTheAppsOwnOriginGivesTheInviteAndTheClientBuildsTheLink() =
        runTest {
            val session = session()
            server.on("/invites", created("${FakeAuthServer.ORIGIN}/invite#$code"))
            val invite = assertIs<AuthResult.Ok<CreatedInvite>>(CreateInvite(api)(session)).value
            assertEquals(id, invite.inviteId)
            assertEquals(code, invite.code.reveal())
            assertEquals("${FakeAuthServer.ORIGIN}/invite#$code", invite.link)
            assertFalse(code in invite.toString(), "the code never prints")
            assertEquals(
                HttpMethod.Post,
                server.requests
                    .last()
                    .data.method,
            )
        }

    @Test
    fun aLinkThatIsNotExactlyOursIsNeverShownAndTheInviteIsRevoked() =
        runTest {
            val session = session()
            val bad =
                listOf(
                    "https://look-alike.example/invite#$code",
                    "${FakeAuthServer.ORIGIN}/invite#$code/extra",
                    "${FakeAuthServer.ORIGIN}/invite?code=$code",
                    "https://user@${FakeAuthServer.ORIGIN.removePrefix("https://")}/invite#$code",
                    code,
                )
            bad.forEach { link ->
                server.requests.clear()
                server.on("/invites", created(link))
                server.on("/invites/$id", { _ -> respond("", HttpStatusCode.NoContent) })
                assertEquals(AuthResult.Failed(AuthError.Api(ApiError.MALFORMED_RESPONSE)), CreateInvite(api)(session), link)
                val revoke = server.requests.last()
                assertEquals(HttpMethod.Delete, revoke.data.method, link)
                assertEquals("/invites/$id", revoke.path)
            }
        }

    @Test
    fun theInviteLimitIsAClosedError() =
        runTest {
            server.on("/invites", error(HttpStatusCode.Conflict, "invite_limit"))
            assertEquals(AuthResult.Failed(AuthError.Api(ApiError.INVITE_LIMIT)), CreateInvite(api)(session()))
        }

    @Test
    fun aMalformedCreateAnswerIsRefusedAndRevokedWhenItHasAnId() =
        runTest {
            val session = session()
            val link = "${FakeAuthServer.ORIGIN}/invite#$code"
            server.on("/invites/$id", { _ -> respond("", HttpStatusCode.NoContent) })
            listOf(
                """{"inviteId":"$id","link":"$link","expiresAt":0}""",
                """{"inviteId":"$id","link":"$link","expiresAt":253402300800}""",
                """{"inviteId":"$id","link":"${"x".repeat(301)}","expiresAt":1}""",
            ).forEach {
                server.requests.clear()
                server.on("/invites", json(it, HttpStatusCode.Created))
                assertEquals(AuthResult.Failed(AuthError.Api(ApiError.MALFORMED_RESPONSE)), CreateInvite(api)(session), it)
                assertEquals("/invites/$id", server.requests.last().path, "revoked: $it")
                assertEquals(
                    HttpMethod.Delete,
                    server.requests
                        .last()
                        .data.method,
                    it,
                )
            }
            // Without a canonical id there is nothing to revoke: refused, and nothing else is sent.
            server.requests.clear()
            server.on("/invites", json("""{"inviteId":"not-a-uuid","link":"$link","expiresAt":1}""", HttpStatusCode.Created))
            assertEquals(AuthResult.Failed(AuthError.Api(ApiError.MALFORMED_RESPONSE)), CreateInvite(api)(session))
            assertEquals(listOf(HttpMethod.Post), server.requests.map { it.data.method })
        }

    @Test
    fun theListIsReadAndAnUnknownStatusIsKept() =
        runTest {
            val session = session()
            server.on(
                "/invites",
                json(
                    """{"invites":[{"inviteId":"$id","createdAt":1,"expiresAt":2,"status":"used","usedBy":"ben"},""" +
                        """{"inviteId":"$id","createdAt":1,"expiresAt":2,"status":"frozen","usedBy":null}]}""",
                ),
            )
            val list = assertIs<AuthResult.Ok<List<Invite>>>(ListInvites(api)(session)).value
            assertEquals(listOf(InviteState.USED, InviteState.UNKNOWN), list.map { it.status })
            assertEquals("ben", list[0].usedBy)
            assertEquals(
                HttpMethod.Get,
                server.requests
                    .last()
                    .data.method,
            )
            // Any bad entry refuses the whole list: a bad id, a usedBy that isn't a username (it is printed and shown) or
            // sits on an invite that isn't used, a missing usedBy, times that can't be shown or are out of order.
            listOf(
                """"inviteId":"x","createdAt":1,"expiresAt":2,"status":"used","usedBy":"ben"""",
                """"inviteId":"$id","createdAt":1,"expiresAt":2,"status":"used","usedBy":"Ben\u001b[2J"""",
                """"inviteId":"$id","createdAt":1,"expiresAt":2,"status":"open","usedBy":"ben"""",
                """"inviteId":"$id","createdAt":1,"expiresAt":2,"status":"used","usedBy":null""",
                """"inviteId":"$id","createdAt":3,"expiresAt":2,"status":"open","usedBy":null""",
                """"inviteId":"$id","createdAt":1,"expiresAt":253402300800,"status":"open","usedBy":null""",
            ).forEach { fields ->
                server.on("/invites", json("""{"invites":[{$fields}]}"""))
                assertEquals(AuthResult.Failed(AuthError.Api(ApiError.MALFORMED_RESPONSE)), ListInvites(api)(session), fields)
            }
            val one = """{"inviteId":"$id","createdAt":1,"expiresAt":2,"status":"expired","usedBy":null}"""
            server.on("/invites", json("""{"invites":[${List(50) { one }.joinToString(",")}]}"""))
            assertEquals(50, assertIs<AuthResult.Ok<List<Invite>>>(ListInvites(api)(session)).value.size, "the server's 50")
            server.on("/invites", json("""{"invites":[${List(51) { one }.joinToString(",")}]}"""))
            assertEquals(AuthResult.Failed(AuthError.Api(ApiError.MALFORMED_RESPONSE)), ListInvites(api)(session), "more than 50")
        }

    @Test
    fun revokeDeletesThatInviteAndSomethingElseIsNotFound() =
        runTest {
            val session = session()
            server.on("/invites/$id", { _ -> respond("", HttpStatusCode.NoContent) }, error(HttpStatusCode.NotFound, "not_found"))
            assertIs<AuthResult.Ok<Unit>>(RevokeInvite(api)(session, id))
            assertEquals(
                HttpMethod.Delete,
                server.requests
                    .last()
                    .data.method,
            )
            assertEquals(AuthResult.Failed(AuthError.Api(ApiError.NOT_FOUND)), RevokeInvite(api)(session, id))
            assertFailsWith<IllegalArgumentException>("only canonical ids reach the path") { RevokeInvite(api)(session, "../accounts") }
        }
}
