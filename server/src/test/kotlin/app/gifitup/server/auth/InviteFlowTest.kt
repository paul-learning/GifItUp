package app.gifitup.server.auth

import app.gifitup.protocol.ChallengeResponse
import app.gifitup.protocol.LoginRequest
import app.gifitup.protocol.PROTOCOL_VERSION
import app.gifitup.protocol.PROTOCOL_VERSION_HEADER
import app.gifitup.protocol.ProtocolJson
import app.gifitup.protocol.RegisterRequest
import app.gifitup.protocol.SessionResponse
import app.gifitup.server.AdminCommands
import app.gifitup.server.AlwaysReady
import app.gifitup.server.allText
import app.gifitup.server.captureLogs
import app.gifitup.server.db.APP_ROLE
import app.gifitup.server.db.Database
import app.gifitup.server.db.DatabaseConfig
import app.gifitup.server.db.InviteRepository
import app.gifitup.server.db.NewInvite
import app.gifitup.server.db.TestPostgres
import app.gifitup.server.module
import app.gifitup.server.noDatabaseDelivery
import app.gifitup.server.noDatabaseInvites
import app.gifitup.server.noDatabaseKeyPackages
import app.gifitup.server.noDatabaseMembers
import app.gifitup.server.testConfig
import io.ktor.client.HttpClient
import io.ktor.client.plugins.defaultRequest
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
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.security.SecureRandom
import java.time.Duration
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Invites end to end (ADR-0010): the admin CLI, the real redeemer, registration, retention and account removal. */
class InviteFlowTest {
    private val clock = TestClock()
    private val origin = testConfig().publicOrigin

    private inner class Fixture(
        val db: Database,
        val url: String,
        val client: HttpClient,
    ) {
        val admin = AdminCommands(db, origin, clock, SecureRandom())

        /** Runs `create-invite` and returns the code from the printed link. */
        fun adminInvite(): String {
            val out = ByteArrayOutputStream()
            assertEquals(0, admin.run(listOf("create-invite"), PrintStream(out, true, Charsets.UTF_8)))
            val link = out.toString(Charsets.UTF_8).lineSequence().first()
            assertTrue(LINK.matches(link), "link format: ${link.substringBefore('#')}#…")
            return link.substringAfter('#')
        }

        /** An invite created by [account] (inserted directly; `InviteEndpointsTest` covers the endpoint). */
        fun friendInvite(account: UUID): String {
            val code = InviteCodes.generate(SecureRandom())
            db.transaction { c ->
                InviteRepository().insert(
                    c,
                    NewInvite(
                        UUID.randomUUID(),
                        checkNotNull(InviteCodes.hash(code)),
                        account,
                        clock.now,
                        clock.now.plus(Duration.ofDays(7)),
                    ),
                )
            }
            return code
        }

        suspend fun register(
            username: String,
            invite: String,
            device: TestDevice = TestDevice(),
        ): HttpResponse {
            val challenge =
                checkNotNull(
                    Base64Url.decode(
                        ProtocolJson.decodeFromString<ChallengeResponse>(client.post(CHALLENGE_PATH).bodyAsText()).challenge,
                        Challenges.CHALLENGE_SIZE,
                    ),
                )
            val signature = device.sign(authSignContentUnchecked(AuthPurpose.REGISTER, challenge, origin.text, username))
            return client.post(REGISTER_PATH) {
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

        fun accountId(username: String): UUID? =
            row("SELECT id FROM gifitup.accounts WHERE username = ?", username) {
                it.getObject(1, UUID::class.java)
            }

        fun <T> row(
            sql: String,
            vararg params: Any,
            read: (java.sql.ResultSet) -> T,
        ): T? = TestPostgres.adminAt(url).use { c -> c.prepareStatement(sql).use { s -> query(s, params, read) } }

        private fun <T> query(
            statement: java.sql.PreparedStatement,
            params: Array<out Any>,
            read: (java.sql.ResultSet) -> T,
        ): T? {
            params.forEachIndexed { i, p -> statement.setObject(i + 1, p) }
            return statement.executeQuery().use { if (it.next()) read(it) else null }
        }

        /** Polls until some session in this database waits on a lock (up to 10 s), instead of a fixed sleep. */
        fun awaitBlockedOnLock() {
            val blocked =
                (1..LOCK_WAIT_POLLS).any {
                    val waiting =
                        row(
                            "SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() AND wait_event_type = 'Lock'",
                        ) { it.getInt(1) }
                    (checkNotNull(waiting) > 0).also { if (!it) Thread.sleep(LOCK_WAIT_POLL_MILLIS) }
                }
            assertTrue(blocked, "the delete never blocked")
        }

        /** Revokes by hash, now (the endpoint revokes by id; `InviteEndpointsTest` covers it). */
        fun revoke(code: String) {
            TestPostgres.adminAt(url).use { c ->
                c.prepareStatement("UPDATE gifitup.invites SET revoked_at = ? WHERE code_hash = ?").use {
                    it.setObject(1, java.time.OffsetDateTime.ofInstant(clock.now, java.time.ZoneOffset.UTC))
                    it.setBytes(2, InviteCodes.hash(code))
                    it.executeUpdate()
                }
            }
        }

        fun count(table: String): Int = checkNotNull(row("SELECT count(*) FROM gifitup.$table") { it.getInt(1) })
    }

    private fun inviteTest(block: suspend Fixture.() -> Unit) {
        val url = TestPostgres.migratedDatabase()
        Database.connect(DatabaseConfig(url, APP_ROLE, TestPostgres.appPasswordFile())).use { db ->
            val auth = AuthService(db, origin, Challenges(clock), DbInviteRedeemer(), clock)
            testApplication {
                application {
                    module(
                        testConfig(),
                        AlwaysReady,
                        auth,
                        noDatabaseInvites(),
                        noDatabaseKeyPackages(),
                        noDatabaseMembers(),
                        noDatabaseDelivery(),
                    )
                }
                val client = createClient { defaultRequest { header(PROTOCOL_VERSION_HEADER, PROTOCOL_VERSION.toString()) } }
                Fixture(db, url, client).block()
            }
        }
    }

    @Test
    fun anAdminInviteRegistersABootstrapRootOnce() =
        inviteTest {
            val code = adminInvite()
            val response = register("ada", code)
            assertEquals(HttpStatusCode.Created, response.status)
            assertEquals("no-store", response.headers[HttpHeaders.CacheControl], "SEC-48")

            val ada = assertNotNull(accountId("ada"))
            assertEquals(true, row("SELECT joined_via_admin FROM gifitup.accounts WHERE id = ?", ada) { it.getBoolean(1) })
            assertNull(row("SELECT invited_by FROM gifitup.accounts WHERE id = ?", ada) { it.getObject(1) })
            assertEquals(ada, row("SELECT used_by FROM gifitup.invites") { it.getObject(1, UUID::class.java) })
            assertEquals(HttpStatusCode.Forbidden, register("ada2", code).status, "single use")
        }

    @Test
    fun aFriendsInviteRecordsTheInviteTree() =
        inviteTest {
            register("bea", adminInvite())
            val bea = assertNotNull(accountId("bea"))

            assertEquals(HttpStatusCode.Created, register("cal", friendInvite(bea)).status)
            val cal = assertNotNull(accountId("cal"))
            assertEquals(bea, row("SELECT invited_by FROM gifitup.accounts WHERE id = ?", cal) { it.getObject(1, UUID::class.java) })
            assertEquals(false, row("SELECT joined_via_admin FROM gifitup.accounts WHERE id = ?", cal) { it.getBoolean(1) })
        }

    @Test
    fun theCodeIsStoredOnlyAsAHashAndNeverLogged() =
        captureLogs { logs ->
            inviteTest {
                val code = adminInvite()
                register("dan", code)
                register("dan2", code)
                val raw = checkNotNull(Base64Url.decode(code, InviteCodes.CODE_BYTES))
                assertEquals(0, checkNotNull(row("SELECT count(*) FROM gifitup.invites WHERE code_hash = ?", raw) { it.getInt(1) }))
                assertEquals(
                    1,
                    checkNotNull(
                        row(
                            "SELECT count(*) FROM gifitup.invites WHERE code_hash = ?",
                            checkNotNull(InviteCodes.hash(code)),
                        ) {
                            it.getInt(1)
                        },
                    ),
                )
                assertFalse(logs.allText().contains(code), "server log")
                assertFalse(TestPostgres.serverLog().contains(code), "Postgres log")
            }
        }

    @Test
    fun expiredRevokedUnknownAndOrphanedInvitesAllGetTheSame403() =
        inviteTest {
            val expired = adminInvite()
            clock.advance(AdminCommands.ADMIN_INVITE_LIFETIME.plusSeconds(1))
            val revoked = adminInvite()
            revoke(revoked)
            register("eve", adminInvite())
            val orphaned = friendInvite(assertNotNull(accountId("eve")))
            TestPostgres.adminAt(url).use { it.createStatement().executeUpdate("DELETE FROM gifitup.accounts WHERE username = 'eve'") }

            listOf(
                expired to "expired",
                revoked to "revoked",
                InviteCodes.generate(SecureRandom()) to "unknown",
                orphaned to "creator deleted (SEC-52c)",
            ).forEach { (code, why) ->
                val response = register("fay", code)
                assertEquals(HttpStatusCode.Forbidden, response.status, why)
                assertEquals("""{"error":"invite_invalid"}""", response.bodyAsText(), why)
            }
        }

    /** SEC-52d: the check is the strict decode, not only the alphabet and length. */
    @Test
    fun aNonCanonicalCodeIsABadRequest() =
        inviteTest {
            val canonical = adminInvite()
            // The last character carries 4 unused bits; flipping one gives a 22-char string that doesn't round-trip.
            val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
            val last = alphabet.indexOf(canonical.last())
            val nonCanonical = canonical.dropLast(1) + alphabet[last xor 1]
            assertEquals(HttpStatusCode.BadRequest, register("gus", nonCanonical).status)
            assertEquals(HttpStatusCode.BadRequest, register("gus", "short").status)
        }

    /** #12 SEC-3 against the real redeemer: a taken name with a valid invite is a 409 and the invite stays unused. */
    @Test
    fun aTakenUsernameLeavesTheInviteUnused() =
        inviteTest {
            register("hal", adminInvite())
            val second = adminInvite()

            assertEquals(HttpStatusCode.Conflict, register("hal", second).status)
            assertEquals(1, checkNotNull(row("SELECT count(*) FROM gifitup.invites WHERE used_at IS NULL") { it.getInt(1) }))
            assertEquals(HttpStatusCode.Created, register("hal2", second).status, "still usable")
        }

    /** SEC-52b: two registrations racing for one invite; exactly one wins. */
    @Test
    fun aRaceForOneInviteHasExactlyOneWinner() =
        inviteTest {
            val code = adminInvite()
            val hash = checkNotNull(InviteCodes.hash(code))
            val results = java.util.concurrent.ConcurrentLinkedQueue<Boolean>()
            val start = java.util.concurrent.CountDownLatch(1)
            val threads =
                List(6) {
                    Thread {
                        start.await()
                        results.add(db.transaction { c -> InviteRepository().redeem(c, hash, clock.now) } != null)
                    }.apply { start() }
                }
            start.countDown()
            threads.forEach { it.join() }
            assertEquals(1, results.count { it })
        }

    @Test
    fun retentionKeepsUsedInvitesThirtyDaysAndEndedOnesOne() =
        inviteTest {
            register("ivy", adminInvite())
            adminInvite() // will expire unused
            revoke(adminInvite())
            val cleanup = InviteCleanup(db, clock)

            clock.advance(Duration.ofDays(1))
            assertEquals(0, cleanup.runOnce(), "the revoked one is kept for a day")
            clock.advance(Duration.ofSeconds(1))
            assertEquals(1, cleanup.runOnce(), "the revoked one, more than a day after revocation")
            clock.advance(Duration.ofDays(1))
            assertEquals(1, cleanup.runOnce(), "the expired one, more than a day after it ended")
            assertEquals(1, count("invites"))
            clock.advance(Duration.ofDays(27))
            assertEquals(0, cleanup.runOnce(), "the used one is kept for 30 days")
            clock.advance(Duration.ofDays(1))
            assertEquals(1, cleanup.runOnce(), "the used one, after 30 days")
            assertEquals(0, count("invites"))
            assertNotNull(accountId("ivy"), "accounts are never touched")
        }

    @Test
    fun deleteAccountRemovesOneAndWithInviteesRemovesTheSubtree() =
        inviteTest {
            register("jon", adminInvite())
            register("kim", friendInvite(assertNotNull(accountId("jon"))))
            register("leo", friendInvite(assertNotNull(accountId("kim"))))
            register("max", adminInvite())
            val out = PrintStream(ByteArrayOutputStream(), true)

            assertEquals(0, admin.run(listOf("delete-account", "--with-invitees", "kim"), out))
            assertNull(accountId("kim"))
            assertNull(accountId("leo"), "invited by kim")
            assertNotNull(accountId("jon"), "kim's inviter stays")
            assertNotNull(accountId("max"), "unrelated")

            assertEquals(0, admin.run(listOf("delete-account", "jon"), out))
            assertNull(accountId("jon"))
            assertEquals(1, admin.run(listOf("delete-account", "nobody"), out), "unknown")
            assertEquals(2, admin.run(listOf("delete-account"), out), "usage")
            assertEquals(2, admin.run(emptyList(), out), "usage, no arguments")
            assertEquals(2, admin.run(listOf("delete-account", "--with-invitees"), out), "usage, missing name")
            assertEquals(0, count("devices") - 1, "only max's device is left")
        }

    /** #74 SEC-53: an invitee whose registration is in flight during `--with-invitees` doesn't escape the delete. */
    @Test
    fun aRegistrationInFlightDoesNotEscapeASubtreeDelete() =
        inviteTest {
            register("oli", adminInvite())
            val oli = assertNotNull(accountId("oli"))
            TestPostgres.adminAt(url).use { pending ->
                pending.autoCommit = false
                pending
                    .prepareStatement(
                        "INSERT INTO gifitup.accounts (id, username, created_at, invited_by) VALUES (?, 'pia', now(), ?)",
                    ).use {
                        it.setObject(1, UUID.randomUUID())
                        it.setObject(2, oli)
                        it.executeUpdate()
                    }
                val deleted =
                    java.util.concurrent.CompletableFuture.supplyAsync {
                        admin.run(listOf("delete-account", "--with-invitees", "oli"), PrintStream(ByteArrayOutputStream(), true))
                    }
                awaitBlockedOnLock() // the delete has started and waits for `pending` (#74 SEC-61)
                pending.commit()
                assertEquals(0, deleted.get())
            }
            assertNull(accountId("oli"))
            assertNull(accountId("pia"), "registered while the delete ran")
        }

    @Test
    fun loginResponsesAreNotCacheable() =
        inviteTest {
            val device = TestDevice()
            val session = ProtocolJson.decodeFromString<SessionResponse>(register("nia", adminInvite(), device).bodyAsText())
            val challenge =
                checkNotNull(
                    Base64Url.decode(
                        ProtocolJson.decodeFromString<ChallengeResponse>(client.post(CHALLENGE_PATH).bodyAsText()).challenge,
                        Challenges.CHALLENGE_SIZE,
                    ),
                )
            val signature = device.sign(authSignContentUnchecked(AuthPurpose.LOGIN, challenge, origin.text, session.deviceId))
            val login =
                client.post(LOGIN_PATH) {
                    contentType(ContentType.Application.Json)
                    setBody(
                        ProtocolJson.encodeToString(
                            LoginRequest(session.deviceId, Base64Url.encode(challenge), Base64Url.encode(signature)),
                        ),
                    )
                }
            assertEquals(HttpStatusCode.OK, login.status)
            assertEquals("no-store", login.headers[HttpHeaders.CacheControl])
        }

    private companion object {
        const val LOCK_WAIT_POLLS = 500
        const val LOCK_WAIT_POLL_MILLIS = 20L
        val LINK = Regex("""^https://gifitup\.example\.org/invite#[A-Za-z0-9_-]{22}$""")
    }
}
