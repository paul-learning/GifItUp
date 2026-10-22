package app.gifitup.server.invites

import app.gifitup.protocol.ChallengeResponse
import app.gifitup.protocol.CreateInviteResponse
import app.gifitup.protocol.InviteListResponse
import app.gifitup.protocol.InviteStatus
import app.gifitup.protocol.PROTOCOL_VERSION
import app.gifitup.protocol.PROTOCOL_VERSION_HEADER
import app.gifitup.protocol.ProtocolJson
import app.gifitup.protocol.RegisterRequest
import app.gifitup.protocol.SessionResponse
import app.gifitup.server.AlwaysReady
import app.gifitup.server.allText
import app.gifitup.server.auth.AuthLimits
import app.gifitup.server.auth.AuthPurpose
import app.gifitup.server.auth.AuthService
import app.gifitup.server.auth.Base64Url
import app.gifitup.server.auth.Challenges
import app.gifitup.server.auth.DbInviteRedeemer
import app.gifitup.server.auth.InviteCodes
import app.gifitup.server.auth.KeyedRateLimiter
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
import app.gifitup.server.module
import app.gifitup.server.noDatabaseKeyPackages
import app.gifitup.server.noDatabaseMembers
import app.gifitup.server.testConfig
import io.ktor.client.HttpClient
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
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
import java.security.SecureRandom
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `POST`/`GET`/`DELETE /invites` against the real database (ADR-0010, #13 PR B). */
class InviteEndpointsTest {
    private val clock = TestClock()
    private val origin = testConfig().publicOrigin

    private inner class Fixture(
        val db: Database,
        val url: String,
        val client: HttpClient,
        val service: InviteService,
    ) {
        private val repository = InviteRepository()

        /** Inserts an invite directly and returns its code; [createdBy] null = an admin invite. */
        fun insertInvite(createdBy: UUID?): Pair<UUID, String> {
            val code = InviteCodes.generate(SecureRandom())
            val id = UUID.randomUUID()
            db.transaction { c ->
                repository.insert(
                    c,
                    NewInvite(id, checkNotNull(InviteCodes.hash(code)), createdBy, clock.now, clock.now.plus(Duration.ofDays(1))),
                )
            }
            return id to code
        }

        suspend fun register(
            username: String,
            invite: String = insertInvite(null).second,
            device: TestDevice = TestDevice(),
        ): HttpResponse {
            val challenge =
                checkNotNull(
                    Base64Url.decode(
                        ProtocolJson.decodeFromString<ChallengeResponse>(client.post("/auth/challenge").bodyAsText()).challenge,
                        Challenges.CHALLENGE_SIZE,
                    ),
                )
            val signature = device.sign(authSignContentUnchecked(AuthPurpose.REGISTER, challenge, origin.text, username))
            return client.post("/accounts") {
                contentType(ContentType.Application.Json)
                setBody(
                    ProtocolJson.encodeToString(
                        RegisterRequest(
                            username,
                            invite,
                            Base64Url.encode(device.publicKey),
                            Base64Url.encode(challenge),
                            Base64Url.encode(signature),
                        ),
                    ),
                )
            }
        }

        /** Registers [username] with an admin invite and returns its session token. */
        suspend fun member(username: String): String =
            ProtocolJson.decodeFromString<SessionResponse>(register(username).bodyAsText()).sessionToken

        fun accountId(username: String): UUID =
            TestPostgres.adminAt(url).use { c ->
                c.prepareStatement("SELECT id FROM gifitup.accounts WHERE username = ?").use { firstId(it, username) }
            }

        private fun firstId(
            statement: java.sql.PreparedStatement,
            username: String,
        ): UUID {
            statement.setString(1, username)
            return statement.executeQuery().use { rows ->
                check(rows.next()) { "no such account" }
                rows.getObject(1, UUID::class.java)
            }
        }

        suspend fun create(token: String): HttpResponse = client.post(INVITES_PATH) { bearerAuth(token) }

        suspend fun created(token: String): CreateInviteResponse =
            ProtocolJson.decodeFromString(
                create(token)
                    .also {
                        assertEquals(HttpStatusCode.Created, it.status)
                    }.bodyAsText(),
            )

        suspend fun list(token: String): InviteListResponse =
            ProtocolJson.decodeFromString(
                client
                    .get(INVITES_PATH) {
                        bearerAuth(token)
                    }.bodyAsText(),
            )

        suspend fun revoke(
            token: String,
            id: String,
        ): HttpResponse = client.delete("$INVITES_PATH/$id") { bearerAuth(token) }
    }

    private fun invitesTest(
        limits: AuthLimits = AuthLimits(clock),
        block: suspend Fixture.() -> Unit,
    ) {
        val url = TestPostgres.migratedDatabase()
        Database.connect(DatabaseConfig(url, APP_ROLE, TestPostgres.appPasswordFile())).use { db ->
            val auth = AuthService(db, origin, Challenges(clock), DbInviteRedeemer(), clock, limits)
            val invites = InviteService(db, origin, clock)
            testApplication {
                application { module(testConfig(), AlwaysReady, auth, invites, noDatabaseKeyPackages(), noDatabaseMembers()) }
                val client = createClient { defaultRequest { header(PROTOCOL_VERSION_HEADER, PROTOCOL_VERSION.toString()) } }
                Fixture(db, url, client, invites).block()
            }
        }
    }

    @Test
    fun aMemberCreatesAnInviteAFriendRegistersWithIt() =
        invitesTest {
            val ana = member("ana")
            val response = create(ana)
            assertEquals(HttpStatusCode.Created, response.status)
            assertEquals("no-store", response.headers[HttpHeaders.CacheControl], "SEC-48")
            val invite = ProtocolJson.decodeFromString<CreateInviteResponse>(response.bodyAsText())
            assertTrue(LINK.matches(invite.link))
            assertEquals(clock.now.plus(InviteService.LIFETIME).epochSecond, invite.expiresAt)

            assertEquals(HttpStatusCode.Created, register("ben", invite.link.substringAfter('#')).status)
            val listed = list(ana).invites.single()
            assertEquals(InviteStatus.USED, listed.status)
            assertEquals("ben", listed.usedBy)
            assertEquals(invite.inviteId, listed.inviteId)
        }

    @Test
    fun theSixthOpenInviteIsRefusedUntilOneIsRevoked() =
        invitesTest {
            val cat = member("cat")
            val first = created(cat)
            repeat(InviteService.MAX_OPEN - 1) { created(cat) }

            val sixth = create(cat)
            assertEquals(HttpStatusCode.Conflict, sixth.status)
            assertEquals("""{"error":"invite_limit"}""", sixth.bodyAsText())
            assertEquals(HttpStatusCode.NoContent, revoke(cat, first.inviteId).status)
            assertEquals(HttpStatusCode.Created, create(cat).status)
        }

    @Test
    fun expiredInvitesFreeTheirSlots() =
        invitesTest {
            val dee = member("dee")
            repeat(InviteService.MAX_OPEN) { created(dee) }
            clock.advance(InviteService.LIFETIME) // the session expires too: the service directly from here
            assertTrue(service.create(accountId("dee")) is CreateOutcome.Created)
        }

    /** SEC-51: the count happens under the account-row lock, so a burst can't overshoot. */
    @Test
    fun concurrentCreatesNeverExceedTheLimit() =
        invitesTest {
            member("eli")
            val eli = accountId("eli")
            val createdCount = AtomicInteger()
            val start = CountDownLatch(1)
            val threads =
                List(12) {
                    Thread {
                        start.await()
                        if (service.create(eli) is CreateOutcome.Created) createdCount.incrementAndGet()
                    }.apply { start() }
                }
            start.countDown()
            threads.forEach { it.join() }
            assertEquals(InviteService.MAX_OPEN, createdCount.get())
        }

    @Test
    fun creationIsCappedPerDayEvenWhenRevokedRightAway() =
        invitesTest {
            val fin = member("fin")
            repeat(InviteService.MAX_CREATED_PER_DAY) { assertEquals(HttpStatusCode.NoContent, revoke(fin, created(fin).inviteId).status) }

            val capped = create(fin)
            assertEquals(HttpStatusCode.TooManyRequests, capped.status)
            assertEquals("""{"error":"rate_limited"}""", capped.bodyAsText())
            clock.advance(InviteService.CREATION_WINDOW.plusSeconds(1)) // the session expires too: the service directly
            assertTrue(service.create(accountId("fin")) is CreateOutcome.Created)
        }

    @Test
    fun theListShowsEachStatusNeverACodeAndAtMostFifty() =
        invitesTest {
            val gil = member("gil")
            val revoked = created(gil)
            revoke(gil, revoked.inviteId)
            clock.advance(Duration.ofSeconds(1))
            val open = created(gil)
            clock.advance(Duration.ofSeconds(1))
            val body = client.get(INVITES_PATH) { bearerAuth(gil) }
            assertEquals("no-store", body.headers[HttpHeaders.CacheControl])
            val text = body.bodyAsText()
            listOf(revoked, open).forEach { assertFalse(text.contains(it.link.substringAfter('#')), "no code in the list") }
            assertEquals(
                listOf(open.inviteId to InviteStatus.OPEN, revoked.inviteId to InviteStatus.REVOKED),
                ProtocolJson.decodeFromString<InviteListResponse>(text).invites.map { it.inviteId to it.status },
                "newest first",
            )
            val gilId = accountId("gil")
            clock.advance(InviteService.LIFETIME) // the session expires too: the service directly from here
            assertEquals(InviteStatus.EXPIRED, service.list(gilId).single { it.id.toString() == open.inviteId }.status)

            val inserted =
                List(InviteService.LIST_LIMIT + 5) {
                    clock.advance(Duration.ofSeconds(1))
                    insertInvite(gilId).first
                }
            assertEquals(
                inserted.reversed().take(InviteService.LIST_LIMIT),
                service.list(gilId).map { it.id },
                "the newest 50, newest first",
            )
        }

    @Test
    fun aRevokedInviteNoLongerRegisters() =
        invitesTest {
            val hal = member("hal")
            val invite = created(hal)
            assertEquals(HttpStatusCode.NoContent, revoke(hal, invite.inviteId).status)
            assertEquals(HttpStatusCode.Forbidden, register("ida", invite.link.substringAfter('#')).status)
            assertEquals(HttpStatusCode.NotFound, revoke(hal, invite.inviteId).status, "already revoked")
        }

    /** One 404 for everything that isn't the caller's own open invite, so ids can't be probed (SEC-52a for admin invites). */
    @Test
    fun othersUsedAdminAndUnknownInvitesAreAll404() =
        invitesTest {
            val joe = member("joe")
            val kai = member("kai")
            val kaisInvite = created(kai)
            val used = created(joe).also { register("lea", it.link.substringAfter('#')) }
            val (adminInvite, _) = insertInvite(null)

            listOf(
                kaisInvite.inviteId to "someone else's",
                used.inviteId to "used",
                adminInvite.toString() to "admin",
                UUID.randomUUID().toString() to "unknown",
            ).forEach { (id, why) ->
                val response = revoke(joe, id)
                assertEquals(HttpStatusCode.NotFound, response.status, why)
                assertEquals("""{"error":"not_found"}""", response.bodyAsText(), why)
            }
            assertEquals(InviteStatus.OPEN, list(kai).invites.single().status, "kai's invite untouched")
            assertTrue(list(joe).invites.none { it.inviteId == adminInvite.toString() }, "admin invites are nobody's")
        }

    @Test
    fun anExpiredInviteCannotBeRevokedAndADeletedAccountCannotCreate() =
        invitesTest {
            val ola = member("ola")
            val olaId = accountId("ola")
            val invite = created(ola)
            clock.advance(InviteService.LIFETIME) // the session expires too: the service directly from here
            assertFalse(service.revoke(olaId, UUID.fromString(invite.inviteId)), "expired")

            TestPostgres.adminAt(url).use { it.createStatement().executeUpdate("DELETE FROM gifitup.accounts WHERE username = 'ola'") }
            assertEquals(CreateOutcome.NoAccount, service.create(olaId))
        }

    @Test
    fun aMalformedIdIsABadRequest() =
        invitesTest {
            val max = member("max")
            val id = created(max).inviteId
            listOf("1-1-1-1-1", id.uppercase(), "$id-0", "not-an-id").forEach {
                assertEquals(HttpStatusCode.BadRequest, revoke(max, it).status, it)
            }
        }

    @Test
    fun everyInviteEndpointNeedsASession() =
        invitesTest {
            assertEquals(HttpStatusCode.Unauthorized, client.post(INVITES_PATH).status)
            assertEquals(HttpStatusCode.Unauthorized, client.get(INVITES_PATH).status)
            assertEquals(HttpStatusCode.Unauthorized, client.delete("$INVITES_PATH/${UUID.randomUUID()}").status)
            assertEquals(HttpStatusCode.Unauthorized, client.post(INVITES_PATH) { bearerAuth("not-a-session") }.status)
        }

    @Test
    fun codesAndLinksNeverReachTheLogs() =
        captureLogs { logs ->
            invitesTest {
                val ned = member("ned")
                val invite = created(ned)
                list(ned)
                revoke(ned, invite.inviteId)
                val code = invite.link.substringAfter('#')
                assertTrue(logs.allText().contains("event=invite_created"))
                assertFalse(logs.allText().contains(code), "server log")
                assertFalse(logs.allText().contains(invite.inviteId), "no invite ids either")
                assertFalse(TestPostgres.serverLog().contains(code), "Postgres log")
            }
        }

    // The per-account request budget (#76), with a budget of 3 and one token per minute.
    private fun budgetTest(block: suspend Fixture.() -> Unit) =
        invitesTest(AuthLimits(clock, accountRequests = KeyedRateLimiter(clock, BUDGET, Duration.ofMinutes(1))), block)

    @Test
    fun theBudgetIsSharedByAnAccountsRoutesAndRefills() =
        budgetTest {
            val pat = member("pat")
            val quinn = member("quinn")
            assertEquals(HttpStatusCode.OK, client.get(INVITES_PATH) { bearerAuth(pat) }.status)
            assertEquals(HttpStatusCode.OK, client.get("/devices") { bearerAuth(pat) }.status)
            assertEquals(HttpStatusCode.OK, client.get(INVITES_PATH) { bearerAuth(pat) }.status)

            val over = client.get("/devices") { bearerAuth(pat) }
            assertEquals(HttpStatusCode.TooManyRequests, over.status)
            assertEquals("""{"error":"rate_limited"}""", over.bodyAsText())
            assertEquals(HttpStatusCode.OK, client.get(INVITES_PATH) { bearerAuth(quinn) }.status, "another account")
            assertEquals(HttpStatusCode.OK, client.get("/health").status, "public paths don't spend it")
            assertEquals(HttpStatusCode.OK, client.post("/auth/challenge").status, "public paths don't spend it")
            assertEquals(
                HttpStatusCode.Unauthorized,
                client.get(INVITES_PATH) { bearerAuth("not-a-session") }.status,
                "no session: 401, never 429",
            )

            clock.advance(Duration.ofMinutes(1))
            assertEquals(HttpStatusCode.OK, client.get(INVITES_PATH) { bearerAuth(pat) }.status, "refilled")
        }

    /** #76 SEC-66: a throttled request never reaches its route. */
    @Test
    fun aThrottledRequestDoesNotRun() =
        budgetTest {
            val rae = member("rae")
            val invite = created(rae)
            repeat(BUDGET - 1) { client.get("/devices") { bearerAuth(rae) } }

            assertEquals(HttpStatusCode.TooManyRequests, revoke(rae, invite.inviteId).status)
            clock.advance(Duration.ofMinutes(1))
            assertEquals(InviteStatus.OPEN, list(rae).invites.single().status, "the revoke didn't run")
        }

    /** #76 SEC-65: a budget drained by one session (say, a stolen one) doesn't stop any session from logging itself out. */
    @Test
    fun logoutIsExemptFromTheBudget() =
        budgetTest {
            val device = TestDevice()
            val stolen = ProtocolJson.decodeFromString<SessionResponse>(register("sam", device = device).bodyAsText()).sessionToken
            // The same key and username again: the lost-reply retry, a second session of the same device (#73 CR-1).
            val owner = ProtocolJson.decodeFromString<SessionResponse>(register("sam", device = device).bodyAsText()).sessionToken
            repeat(BUDGET) { client.get("/devices") { bearerAuth(stolen) } }
            assertEquals(
                HttpStatusCode.TooManyRequests,
                client.get("/devices") { bearerAuth(owner) }.status,
                "drained for the whole account",
            )

            assertEquals(
                HttpStatusCode.TooManyRequests,
                client.get("/auth/logout") { bearerAuth(stolen) }.status,
                "only POST is exempt (#77 SEC-70)",
            )
            assertEquals(HttpStatusCode.NoContent, client.post("/auth/logout") { bearerAuth(stolen) }.status)
            assertEquals(HttpStatusCode.NoContent, client.post("/auth/logout") { bearerAuth(owner) }.status)
        }

    private companion object {
        const val BUDGET = 3
        val LINK = Regex("""^https://gifitup\.example\.org/invite#[A-Za-z0-9_-]{22}$""")
    }
}
