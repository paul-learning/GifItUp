package app.gifitup.server.members

import app.gifitup.protocol.ChallengeResponse
import app.gifitup.protocol.MemberRelation
import app.gifitup.protocol.MembersResponse
import app.gifitup.protocol.PROTOCOL_VERSION
import app.gifitup.protocol.PROTOCOL_VERSION_HEADER
import app.gifitup.protocol.ProtocolJson
import app.gifitup.protocol.RegisterRequest
import app.gifitup.protocol.SessionResponse
import app.gifitup.server.AlwaysReady
import app.gifitup.server.auth.AuthPurpose
import app.gifitup.server.auth.AuthService
import app.gifitup.server.auth.Base64Url
import app.gifitup.server.auth.Challenges
import app.gifitup.server.auth.DbInviteRedeemer
import app.gifitup.server.auth.InviteCodes
import app.gifitup.server.auth.TestClock
import app.gifitup.server.auth.TestDevice
import app.gifitup.server.auth.authSignContentUnchecked
import app.gifitup.server.db.APP_ROLE
import app.gifitup.server.db.Database
import app.gifitup.server.db.DatabaseConfig
import app.gifitup.server.db.InviteRepository
import app.gifitup.server.db.NewInvite
import app.gifitup.server.db.TestPostgres
import app.gifitup.server.invites.InviteService
import app.gifitup.server.module
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
import java.security.SecureRandom
import java.sql.Timestamp
import java.time.Duration
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `GET /members` against the real database (ADR-0014): the caller's inviter and invitees, nobody else. */
class MemberEndpointsTest {
    private val clock = TestClock()
    private val origin = testConfig().publicOrigin

    private inner class Member(
        val name: String,
        val token: String,
        val device: TestDevice,
    )

    private inner class Fixture(
        val db: Database,
        val url: String,
        val client: HttpClient,
    ) {
        private val repository = InviteRepository()

        /** Registers [username] with an invite created by [inviter] (null: an admin invite). */
        suspend fun member(
            username: String,
            inviter: Member? = null,
        ): Member {
            val code = InviteCodes.generate(SecureRandom())
            db.transaction { c ->
                repository.insert(
                    c,
                    NewInvite(
                        UUID.randomUUID(),
                        checkNotNull(InviteCodes.hash(code)),
                        inviter?.let { accountId(it.name) },
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
            return Member(username, ProtocolJson.decodeFromString<SessionResponse>(response.bodyAsText()).sessionToken, device)
        }

        fun accountId(username: String): UUID =
            TestPostgres.adminAt(url).use { c ->
                c.prepareStatement("SELECT id FROM gifitup.accounts WHERE username = ?").use {
                    it.setString(1, username)
                    it.executeQuery().use { rows ->
                        check(rows.next()) { "no such account" }
                        rows.getObject(1, UUID::class.java)
                    }
                }
            }

        fun admin(sql: String) = TestPostgres.adminAt(url).use { c -> c.createStatement().use { it.executeUpdate(sql) } }

        suspend fun get(member: Member?): HttpResponse = client.get("/members") { member?.let { bearerAuth(it.token) } }

        suspend fun members(member: Member): MembersResponse {
            val response = get(member)
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
            return ProtocolJson.decodeFromString(response.bodyAsText())
        }
    }

    private fun membersTest(block: suspend Fixture.() -> Unit) {
        val url = TestPostgres.migratedDatabase()
        Database.connect(DatabaseConfig(url, APP_ROLE, TestPostgres.appPasswordFile())).use { db ->
            val auth = AuthService(db, origin, Challenges(clock), DbInviteRedeemer(), clock)
            testApplication {
                application {
                    module(testConfig(), AlwaysReady, auth, InviteService(db, origin, clock), noDatabaseKeyPackages(), MemberService(db))
                }
                val client = createClient { defaultRequest { header(PROTOCOL_VERSION_HEADER, PROTOCOL_VERSION.toString()) } }
                Fixture(db, url, client).block()
            }
        }
    }

    @Test
    fun listsTheInviterFirstThenTheInviteesByUsernameWithTheirDevices() =
        membersTest {
            val root = member("root")
            val ana = member("ana", root)
            member("zed", ana)
            val bob = member("bob", ana)
            val answer = members(ana)
            assertFalse(answer.more)
            assertEquals(listOf("root", "bob", "zed"), answer.members.map { it.username })
            assertEquals(listOf(MemberRelation.INVITER, MemberRelation.INVITEE, MemberRelation.INVITEE), answer.members.map { it.relation })
            assertEquals(accountId("root").toString(), answer.members[0].accountId)
            val device = answer.members[1].devices.single()
            assertContentEquals(bob.device.publicKey, Base64Url.decode(device.signatureKey, 32))
        }

    @Test
    fun anAccountWithoutAnInviterOrInviteesGetsAnEmptyList() =
        membersTest {
            val root = member("root")
            assertEquals(MembersResponse(emptyList(), more = false), members(root))
        }

    @Test
    fun onlyDirectNeighboursAreListedNeverGrandparentsSiblingsOrGrandchildren() =
        membersTest {
            val root = member("root")
            val ana = member("ana", root)
            val ben = member("ben", root)
            val cat = member("cat", ana)
            val dan = member("dan", cat)
            assertEquals(listOf("ana", "ben"), members(root).members.map { it.username })
            assertEquals(listOf("root", "cat"), members(ana).members.map { it.username })
            assertEquals(listOf("root"), members(ben).members.map { it.username })
            assertEquals(listOf("ana", "dan"), members(cat).members.map { it.username })
            assertEquals(listOf("cat"), members(dan).members.map { it.username })
        }

    @Test
    fun aDeletedInviterIsJustNoInviter() =
        membersTest {
            val root = member("root")
            val ana = member("ana", root)
            admin("DELETE FROM gifitup.accounts WHERE username = 'root'")
            assertEquals(emptyList(), members(ana).members)
        }

    @Test
    fun aRequestWithoutASessionIsRefused() =
        membersTest {
            assertEquals(HttpStatusCode.Unauthorized, get(null).status)
        }

    @Test
    fun aListOverTheLimitIsCutDeterministicallyAndSaysSo() =
        membersTest {
            val root = member("root")
            val rootId = accountId("root")
            repeat(MemberService.LIMIT + 1) { n ->
                val name = "user" + n.toString().padStart(3, '0')
                admin(
                    "INSERT INTO gifitup.accounts (id, username, created_at, invited_by, joined_via_admin) " +
                        "VALUES ('${UUID.randomUUID()}', '$name', now(), '$rootId', false)",
                )
            }
            val first = members(root)
            assertTrue(first.more)
            assertEquals(MemberService.LIMIT, first.members.size)
            assertEquals("user000", first.members.first().username)
            assertEquals("user199", first.members.last().username)
            assertEquals(first, members(root), "the same cut on every request")
        }

    @Test
    fun theInviterSurvivesTheCutWhenTheCallerHasTooManyInvitees() =
        membersTest {
            val root = member("root")
            val ana = member("ana", root)
            val anaId = accountId("ana")
            repeat(MemberService.LIMIT + 1) { n ->
                val name = "user" + n.toString().padStart(3, '0')
                admin(
                    "INSERT INTO gifitup.accounts (id, username, created_at, invited_by, joined_via_admin) " +
                        "VALUES ('${UUID.randomUUID()}', '$name', now(), '$anaId', false)",
                )
            }
            val answer = members(ana)
            assertTrue(answer.more)
            assertEquals(MemberService.LIMIT, answer.members.size)
            assertEquals("root", answer.members.first().username, "the inviter is never the one that is cut")
            assertEquals(MemberRelation.INVITER, answer.members.first().relation)
        }

    @Test
    fun aMemberWithManyDevicesShowsTheOldestTen() =
        membersTest {
            val root = member("root")
            val ana = member("ana", root)
            val anaId = accountId("ana")
            repeat(11) { n ->
                val key = ByteArray(32) { (n + 1).toByte() }.joinToString("") { "%02x".format(it) }
                admin(
                    "INSERT INTO gifitup.devices (id, account_id, signature_key, created_at, last_seen_at) " +
                        "VALUES ('${UUID.randomUUID()}', '$anaId', decode('$key', 'hex'), '${Timestamp.from(
                            clock.now.plusSeconds(n + 1L),
                        )}', now())",
                )
            }
            val devices = members(root).members.single().devices
            assertEquals(10, devices.size)
            assertContentEquals(
                ana.device.publicKey,
                Base64Url.decode(devices.first().signatureKey, 32),
                "the registered device is the oldest",
            )
        }
}
