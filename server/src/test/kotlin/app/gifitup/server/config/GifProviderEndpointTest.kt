package app.gifitup.server.config

import app.gifitup.protocol.ApiErrorCode
import app.gifitup.protocol.ChallengeResponse
import app.gifitup.protocol.ErrorResponse
import app.gifitup.protocol.GifProviderResponse
import app.gifitup.protocol.PROTOCOL_VERSION
import app.gifitup.protocol.PROTOCOL_VERSION_HEADER
import app.gifitup.protocol.ProtocolJson
import app.gifitup.protocol.RegisterRequest
import app.gifitup.protocol.SessionResponse
import app.gifitup.server.AlwaysReady
import app.gifitup.server.allText
import app.gifitup.server.auth.AuthPurpose
import app.gifitup.server.auth.AuthService
import app.gifitup.server.auth.Base64Url
import app.gifitup.server.auth.Challenges
import app.gifitup.server.auth.DbInviteRedeemer
import app.gifitup.server.auth.InviteCodes
import app.gifitup.server.auth.TestClock
import app.gifitup.server.auth.TestDevice
import app.gifitup.server.auth.authSignContentUnchecked
import app.gifitup.server.captureLogs
import app.gifitup.server.db.APP_ROLE
import app.gifitup.server.db.Database
import app.gifitup.server.db.DatabaseConfig
import app.gifitup.server.db.InviteRepository
import app.gifitup.server.db.NewInvite
import app.gifitup.server.db.TestPostgres
import app.gifitup.server.invites.InviteService
import app.gifitup.server.members.MemberService
import app.gifitup.server.module
import app.gifitup.server.noDatabaseDelivery
import app.gifitup.server.noDatabaseKeyPackages
import app.gifitup.server.testConfig
import io.ktor.client.HttpClient
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import java.security.SecureRandom
import java.time.Duration
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `GET /config/gif-provider` against the real database (ADR-0003, #40): sessions only, the same bytes for everyone. */
class GifProviderEndpointTest {
    private val clock = TestClock()
    private val origin = testConfig().publicOrigin
    private val key = "KlipyTestKey_0123456789abcdef"

    private inner class Member(
        val token: String,
    )

    private inner class Fixture(
        val db: Database,
        val client: HttpClient,
    ) {
        private val repository = InviteRepository()

        suspend fun member(username: String): Member {
            val code = InviteCodes.generate(SecureRandom())
            db.transaction { c ->
                repository.insert(
                    c,
                    NewInvite(
                        UUID.randomUUID(),
                        checkNotNull(InviteCodes.hash(code)),
                        null,
                        clock.now,
                        clock.now.plus(Duration.ofDays(1)),
                    ),
                )
            }
            val device = TestDevice()
            val challenge =
                checkNotNull(
                    Base64Url.decode(
                        ProtocolJson.decodeFromString<ChallengeResponse>(client.post("/auth/challenge").bodyAsText()).challenge,
                        Challenges.CHALLENGE_SIZE,
                    ),
                )
            val signature = device.sign(authSignContentUnchecked(AuthPurpose.REGISTER, challenge, origin.text, username))
            val response =
                client.post("/accounts") {
                    contentType(ContentType.Application.Json)
                    setBody(
                        ProtocolJson.encodeToString(
                            RegisterRequest(
                                username,
                                code,
                                Base64Url.encode(device.publicKey),
                                Base64Url.encode(challenge),
                                Base64Url.encode(signature),
                            ),
                        ),
                    )
                }
            assertEquals(HttpStatusCode.Created, response.status)
            return Member(ProtocolJson.decodeFromString<SessionResponse>(response.bodyAsText()).sessionToken)
        }

        suspend fun fetch(token: String?): HttpResponse = client.get("/config/gif-provider") { token?.let { bearerAuth(it) } }
    }

    private fun providerTest(
        endpoint: GifProviderEndpoint,
        block: suspend Fixture.() -> Unit,
    ) {
        val url = TestPostgres.migratedDatabase()
        Database.connect(DatabaseConfig(url, APP_ROLE, TestPostgres.appPasswordFile())).use { db ->
            val auth = AuthService(db, origin, Challenges(clock), DbInviteRedeemer(), clock)
            testApplication {
                application {
                    module(
                        testConfig(),
                        AlwaysReady,
                        auth,
                        InviteService(db, origin, clock),
                        noDatabaseKeyPackages(),
                        MemberService(db),
                        noDatabaseDelivery(),
                        endpoint,
                    )
                }
                val client = createClient { defaultRequest { header(PROTOCOL_VERSION_HEADER, PROTOCOL_VERSION.toString()) } }
                Fixture(db, client).block()
            }
        }
    }

    private fun configured() =
        GifProviderEndpoint.of(
            GifProviderKey.read(
                Files.createTempFile("k", "").also {
                    Files.writeString(it, key)
                },
            ),
            clock,
        )

    @Test
    fun aSessionGetsTheKeyAsExactlyOneFieldAndNoStore() =
        providerTest(configured()) {
            val ana = member("ana")
            val response = fetch(ana.token)
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
            assertEquals(ContentType.Application.Json, response.contentType()?.withoutParameters())
            assertEquals("""{"apiKey":"$key"}""", response.bodyAsText())
            assertEquals(GifProviderResponse(key), ProtocolJson.decodeFromString(response.bodyAsText()))
        }

    @Test
    fun everyAccountGetsTheSameBytes() =
        providerTest(configured()) {
            val ana = member("ana")
            val ben = member("ben")
            assertEquals(fetch(ana.token).bodyAsText(), fetch(ben.token).bodyAsText())
        }

    @Test
    fun noSessionOrABadTokenIsRefusedWithoutTheKey() =
        providerTest(configured()) {
            val none = fetch(null)
            assertEquals(HttpStatusCode.Unauthorized, none.status)
            assertFalse(key in none.bodyAsText())
            val bad = fetch("not-a-token")
            assertEquals(HttpStatusCode.Unauthorized, bad.status)
            assertFalse(key in bad.bodyAsText())
        }

    @Test
    fun anUnconfiguredServerAnswersNotFoundLikeAnOldOne() =
        providerTest(GifProviderEndpoint.unconfigured(clock)) {
            val ana = member("ana")
            val response = fetch(ana.token)
            assertEquals(HttpStatusCode.NotFound, response.status)
            assertEquals(ErrorResponse(ApiErrorCode.NOT_FOUND), ProtocolJson.decodeFromString(response.bodyAsText()))
            assertEquals(HttpStatusCode.Unauthorized, fetch(null).status, "and still not probeable without a session")
        }

    @Test
    fun theElevenFetchOfOneDeviceInTheHourIsLimitedAndAnotherDeviceIsNot() =
        providerTest(configured()) {
            val ana = member("ana")
            val ben = member("ben")
            repeat(10) { assertEquals(HttpStatusCode.OK, fetch(ana.token).status) }
            val limited = fetch(ana.token)
            assertEquals(HttpStatusCode.TooManyRequests, limited.status)
            assertFalse(key in limited.bodyAsText())
            assertEquals(HttpStatusCode.OK, fetch(ben.token).status)
            clock.advance(Duration.ofMinutes(7))
            assertEquals(HttpStatusCode.OK, fetch(ana.token).status, "one token came back")
        }

    @Test
    fun onlyGetIsAnswered() =
        providerTest(configured()) {
            val ana = member("ana")
            val post = client.post("/config/gif-provider") { bearerAuth(ana.token) }
            assertTrue(post.status.value >= 400, "POST is refused")
            assertFalse(key in post.bodyAsText())
        }

    @Test
    fun theKeyIsNeverLogged() =
        captureLogs { logs ->
            providerTest(configured()) {
                val ana = member("ana")
                repeat(12) { fetch(ana.token) }
                fetch(null)
            }
            assertFalse(key in logs.allText(), "no log line carries the key")
        }
}
