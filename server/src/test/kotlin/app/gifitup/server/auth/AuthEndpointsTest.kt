package app.gifitup.server.auth

import app.gifitup.protocol.ChallengeResponse
import app.gifitup.protocol.DeviceListResponse
import app.gifitup.protocol.LoginRequest
import app.gifitup.protocol.PROTOCOL_VERSION
import app.gifitup.protocol.PROTOCOL_VERSION_HEADER
import app.gifitup.protocol.ProtocolJson
import app.gifitup.protocol.RegisterRequest
import app.gifitup.protocol.SessionResponse
import app.gifitup.server.AlwaysReady
import app.gifitup.server.allText
import app.gifitup.server.captureLogs
import app.gifitup.server.db.APP_ROLE
import app.gifitup.server.db.Database
import app.gifitup.server.db.DatabaseConfig
import app.gifitup.server.db.RedeemedInvite
import app.gifitup.server.db.TestPostgres
import app.gifitup.server.module
import app.gifitup.server.testConfig
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.defaultRequest
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
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.time.Duration
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * The auth endpoints end to end (ADR-0009): HTTP through the full module, the production PostgreSQL image, and a test
 * device that signs with the JDK's Ed25519 over the same bytes the crate builds (the crate vectors pin that encoding).
 */
class AuthEndpointsTest {
    private val clock = TestClock()
    private val origin = testConfig().publicOrigin

    /** Accepts the codes in [codes]; each once. In memory, so it can't show rollback of the invite itself (that is #13's). */
    private class TestInvites(
        vararg codes: String,
    ) : InviteRedeemer {
        val unused = codes.toMutableSet()

        override fun redeem(
            connection: java.sql.Connection,
            code: String,
            now: java.time.Instant,
        ): RedeemedInvite? = if (unused.remove(code)) RedeemedInvite(UUID.randomUUID(), null) else null
    }

    private fun authTest(
        invites: InviteRedeemer = TestInvites(INVITE),
        limits: AuthLimits = AuthLimits(clock),
        block: suspend Fixture.() -> Unit,
    ) {
        val url = TestPostgres.migratedDatabase()
        Database.connect(DatabaseConfig(url, APP_ROLE, TestPostgres.appPasswordFile())).use { database ->
            val auth = AuthService(database, origin, Challenges(clock), invites, clock, limits)
            testApplication {
                application { module(testConfig(), AlwaysReady, auth) }
                Fixture(this, url).block()
            }
        }
    }

    private inner class Fixture(
        builder: ApplicationTestBuilder,
        val url: String,
    ) {
        val client: HttpClient = builder.createClient { defaultRequest { header(PROTOCOL_VERSION_HEADER, PROTOCOL_VERSION.toString()) } }

        suspend fun challenge(): ByteArray {
            val response = client.post(CHALLENGE_PATH)
            assertEquals(HttpStatusCode.OK, response.status)
            return checkNotNull(Base64Url.decode(response.json<ChallengeResponse>().challenge, Challenges.CHALLENGE_SIZE))
        }

        suspend fun register(
            device: TestDevice,
            username: String,
            invite: String = INVITE,
            signedOrigin: String = origin.text,
            signedUsername: String = username,
        ): HttpResponse {
            val challenge = challenge()
            val signature = device.sign(authSignContentUnchecked(AuthPurpose.REGISTER, challenge, signedOrigin, signedUsername))
            return postJson(
                REGISTER_PATH,
                ProtocolJson.encodeToString(
                    RegisterRequest(username, invite, b64(device.publicKey), b64(challenge), b64(signature)),
                ),
            )
        }

        @Suppress("LongParameterList") // Each field can be varied by a test.
        suspend fun login(
            device: TestDevice,
            deviceId: String,
            challenge: ByteArray? = null,
            purpose: AuthPurpose = AuthPurpose.LOGIN,
            signedOrigin: String = origin.text,
            signedSubject: String = deviceId,
            headers: Map<String, String> = emptyMap(),
        ): HttpResponse {
            val c = challenge ?: challenge()
            val signature = device.sign(authSignContentUnchecked(purpose, c, signedOrigin, signedSubject))
            return postJson(LOGIN_PATH, ProtocolJson.encodeToString(LoginRequest(deviceId, b64(c), b64(signature))), headers)
        }

        suspend fun postJson(
            path: String,
            body: String,
            headers: Map<String, String> = emptyMap(),
        ): HttpResponse =
            client.post(path) {
                contentType(ContentType.Application.Json)
                headers.forEach { (k, v) -> header(k, v) }
                setBody(body)
            }

        suspend fun devices(token: String?): HttpResponse =
            client.get(DEVICES_PATH) {
                token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            }

        fun count(table: String): Int =
            TestPostgres.adminAt(url).use { db ->
                db.createStatement().executeQuery("SELECT count(*) FROM gifitup.$table").use {
                    it.next()
                    it.getInt(1)
                }
            }
    }

    @Test
    fun registerCreatesAccountDeviceAndASessionThatWorks() =
        authTest {
            val device = TestDevice()
            val response = register(device, "alice")
            assertEquals(HttpStatusCode.Created, response.status)
            val session = response.json<SessionResponse>()
            assertEquals(clock.now.plus(AuthService.SESSION_LIFETIME).epochSecond, session.expiresAt)

            val devices = devices(session.sessionToken)
            assertEquals(HttpStatusCode.OK, devices.status)
            assertEquals(listOf(session.deviceId), devices.json<DeviceListResponse>().devices.map { it.deviceId })
        }

    @Test
    fun theTokenIsStoredOnlyAsAHash() =
        authTest {
            val token = register(TestDevice(), "bob").json<SessionResponse>().sessionToken
            val raw = checkNotNull(Base64Url.decode(token, AuthService.TOKEN_SIZE))
            TestPostgres.adminAt(url).use { db ->
                db.prepareStatement("SELECT count(*) FROM gifitup.sessions WHERE token_hash = ?").use {
                    it.setBytes(1, raw)
                    it.executeQuery().use { rows ->
                        rows.next()
                        assertEquals(0, rows.getInt(1), "raw token in the database")
                    }
                }
                val hash =
                    java.security.MessageDigest
                        .getInstance("SHA-256")
                        .digest(raw)
                db.prepareStatement("SELECT count(*) FROM gifitup.sessions WHERE token_hash = ?").use {
                    it.setBytes(1, hash)
                    it.executeQuery().use { rows ->
                        rows.next()
                        assertEquals(1, rows.getInt(1))
                    }
                }
            }
        }

    @Test
    fun deniedInviteWritesNothing() =
        authTest(invites = DenyAllInviteRedeemer) {
            val response = register(TestDevice(), "carol")

            assertEquals(HttpStatusCode.Forbidden, response.status)
            assertEquals("""{"error":"invite_invalid"}""", response.bodyAsText())
            listOf("accounts", "devices", "sessions").forEach { assertEquals(0, count(it), it) }
        }

    /** #68 SEC-3: a taken username is only revealed to someone with a valid invite. */
    @Test
    fun takenUsernameNeedsAValidInviteToBeRevealed() =
        authTest(invites = TestInvites(INVITE, SECOND)) {
            assertEquals(HttpStatusCode.Created, register(TestDevice(), "dave").status)

            assertEquals(HttpStatusCode.Forbidden, register(TestDevice(), "dave", invite = UNKNOWN_CODE).status, "no oracle")
            val taken = register(TestDevice(), "dave", invite = SECOND)
            assertEquals(HttpStatusCode.Conflict, taken.status)
            assertEquals("""{"error":"username_unavailable"}""", taken.bodyAsText())
            assertEquals(1, count("accounts"))
            assertEquals(1, count("devices"))
            assertEquals(1, count("sessions"))
        }

    @Test
    fun usernameRulesApplyBeforeAnythingElse() =
        authTest {
            assertEquals(HttpStatusCode.BadRequest, register(TestDevice(), "Alice", signedUsername = "alice").status)
            assertEquals(HttpStatusCode.Conflict, register(TestDevice(), "admin").status, "reserved")
            assertEquals(0, count("accounts"))
        }

    @Test
    fun registrationSignatureMustCoverOurOriginAndTheUsername() =
        authTest {
            assertEquals(HttpStatusCode.Unauthorized, register(TestDevice(), "erin", signedOrigin = "https://evil.example").status)
            assertEquals(HttpStatusCode.Unauthorized, register(TestDevice(), "erin", signedUsername = "frank").status)
            assertEquals(0, count("accounts"))
        }

    @Test
    fun loginWorksAndEveryWrongFieldFails() =
        authTest {
            val device = TestDevice()
            val id = register(device, "gina").json<SessionResponse>().deviceId

            assertEquals(HttpStatusCode.OK, login(device, id).status)
            assertEquals(HttpStatusCode.Unauthorized, login(device, id, signedOrigin = "https://evil.example").status, "origin")
            assertEquals(HttpStatusCode.Unauthorized, login(device, id, purpose = AuthPurpose.REGISTER).status, "purpose")
            assertEquals(HttpStatusCode.Unauthorized, login(device, id, signedSubject = UUID.randomUUID().toString()).status, "subject")
            assertEquals(HttpStatusCode.Unauthorized, login(TestDevice(), id).status, "another key")
            assertEquals(HttpStatusCode.Unauthorized, login(device, UUID.randomUUID().toString()).status, "unknown device")
            assertEquals(HttpStatusCode.Unauthorized, login(device, id.uppercase(), signedSubject = id).status, "non-canonical id")
        }

    @Test
    fun aChallengeWorksOnceAndNotAfterItExpires() =
        authTest {
            val device = TestDevice()
            val id = register(device, "hank").json<SessionResponse>().deviceId
            val challenge = challenge()
            assertEquals(HttpStatusCode.OK, login(device, id, challenge).status)
            assertEquals(HttpStatusCode.Unauthorized, login(device, id, challenge).status, "reused")

            val old = challenge()
            clock.advance(Challenges.DEFAULT_TTL.plusSeconds(1))
            assertEquals(HttpStatusCode.Unauthorized, login(device, id, old).status, "expired")
        }

    /** #70 SEC-25: the signed origin is the configured one, whatever the request's Host or forwarding headers say. */
    @Test
    fun requestHeadersNeverChooseTheOrigin() =
        authTest {
            val device = TestDevice()
            val id = register(device, "ivan").json<SessionResponse>().deviceId
            val spoofed =
                mapOf(
                    HttpHeaders.Host to "evil.example",
                    "X-Forwarded-Host" to "evil.example",
                    HttpHeaders.Origin to "https://evil.example",
                )

            assertEquals(HttpStatusCode.Unauthorized, login(device, id, signedOrigin = "https://evil.example", headers = spoofed).status)
            assertEquals(HttpStatusCode.OK, login(device, id, headers = spoofed).status)
        }

    @Test
    fun logoutAndExpiryEndASession() =
        authTest {
            val device = TestDevice()
            val registered = register(device, "jill").json<SessionResponse>()
            val second = login(device, registered.deviceId).json<SessionResponse>().sessionToken

            val logout = client.post(LOGOUT_PATH) { header(HttpHeaders.Authorization, "Bearer ${registered.sessionToken}") }
            assertEquals(HttpStatusCode.NoContent, logout.status)
            assertEquals(HttpStatusCode.Unauthorized, devices(registered.sessionToken).status, "logged out")
            assertEquals(HttpStatusCode.OK, devices(second).status, "other sessions stay")

            clock.advance(AuthService.SESSION_LIFETIME)
            assertEquals(HttpStatusCode.Unauthorized, devices(second).status, "expired")
        }

    @Test
    fun theSixthSessionEndsTheOldest() =
        authTest {
            val device = TestDevice()
            val registered = register(device, "kate").json<SessionResponse>()
            repeat(5) {
                clock.advance(Duration.ofSeconds(1))
                assertEquals(HttpStatusCode.OK, login(device, registered.deviceId).status)
            }

            assertEquals(HttpStatusCode.Unauthorized, devices(registered.sessionToken).status)
            assertEquals(5, count("sessions"))
        }

    /** #73 CR-1: a register whose reply was lost can be retried with the same key and name; no second invite needed. */
    @Test
    fun aRegisterRetryResumesTheSameDevice() =
        authTest {
            val device = TestDevice()
            val first = register(device, "paula").json<SessionResponse>()

            val retry = register(device, "paula", invite = UNKNOWN_CODE)
            assertEquals(HttpStatusCode.Created, retry.status)
            val resumed = retry.json<SessionResponse>()
            assertEquals(first.deviceId, resumed.deviceId)
            assertEquals(HttpStatusCode.OK, devices(resumed.sessionToken).status)
            assertEquals(1, count("accounts"))
            assertEquals(1, count("devices"))
        }

    /** #73 CR-9: a resume never touches the invite it carries. */
    @Test
    fun aResumeLeavesAValidInviteUnused() {
        val invites = TestInvites(INVITE, SPARE)
        authTest(invites = invites) {
            val device = TestDevice()
            register(device, "uma")

            assertEquals(HttpStatusCode.Created, register(device, "uma", invite = SPARE).status)
            assertEquals(setOf(SPARE), invites.unused, "the resume must not redeem the invite")
        }
    }

    /** #73 CR-9: the resume is a login, so it is bound by the same per-device budget. */
    @Test
    fun aResumeIsBoundByTheDeviceBudget() =
        authTest(limits = AuthLimits(clock, deviceLogins = KeyedRateLimiter(clock, capacity = 1, refillEvery = Duration.ofHours(1)))) {
            val device = TestDevice()
            register(device, "vera")
            assertEquals(HttpStatusCode.Created, register(device, "vera").status)
            assertEquals(HttpStatusCode.TooManyRequests, register(device, "vera").status)
        }

    /** #73 CR-7, SEC-39: one genuine challenge, replayed with bad signatures, costs the shared budget at most once. */
    @Test
    fun aReplayedChallengeCostsTheSharedBudgetOnce() =
        authTest(limits = AuthLimits(clock, authAttempts = KeyedRateLimiter(clock, capacity = 3, refillEvery = Duration.ofHours(1)))) {
            val device = TestDevice()
            val id = register(device, "walt").json<SessionResponse>().deviceId // spends 1
            val genuine = challenge()
            repeat(30) { assertEquals(HttpStatusCode.Unauthorized, login(TestDevice(), id, genuine).status) } // spends 1

            assertEquals(HttpStatusCode.OK, login(device, id).status, "one unit of budget is still left")
        }

    /** #73 CR-2: a key belongs to one device; registering it under another name is refused. */
    @Test
    fun aRegisteredKeyCannotRegisterAnotherName() =
        authTest(invites = TestInvites(INVITE, SECOND)) {
            val device = TestDevice()
            register(device, "quinn")

            val other = register(device, "quentin", invite = SECOND)
            assertEquals(HttpStatusCode.BadRequest, other.status)
            assertEquals(1, count("accounts"))
        }

    /** #73 SEC-36: only a caller whose signature checked out learns that a device's budget is used up. */
    @Test
    fun anExhaustedBudgetIsOnlyRevealedToTheKeyHolder() =
        authTest(limits = AuthLimits(clock, deviceLogins = KeyedRateLimiter(clock, capacity = 1, refillEvery = Duration.ofHours(1)))) {
            val device = TestDevice()
            val id = register(device, "rosa").json<SessionResponse>().deviceId
            assertEquals(HttpStatusCode.OK, login(device, id).status)

            assertEquals(HttpStatusCode.Unauthorized, login(TestDevice(), id).status, "wrong key: same 401 as always")
            assertEquals(HttpStatusCode.TooManyRequests, login(device, id).status, "the key holder sees the limit")
        }

    /** #73 SEC-34: requests without a genuine, fresh challenge don't spend the shared login budget. */
    @Test
    fun bogusChallengesDoNotDrainTheGlobalBudget() =
        authTest(limits = AuthLimits(clock, authAttempts = KeyedRateLimiter(clock, capacity = 3, refillEvery = Duration.ofHours(1)))) {
            val device = TestDevice()
            val id = register(device, "sam").json<SessionResponse>().deviceId
            repeat(50) {
                val forged = ByteArray(Challenges.CHALLENGE_SIZE) { i -> (i + it).toByte() }
                assertEquals(HttpStatusCode.Unauthorized, login(device, id, forged).status)
            }

            assertEquals(HttpStatusCode.OK, login(device, id).status)
        }

    /** Deny by default (#68 SEC-6): no session, a malformed one, or two Authorization headers. */
    @Test
    fun protectedPathsNeedExactlyOneValidSession() =
        authTest {
            val token = register(TestDevice(), "liam").json<SessionResponse>().sessionToken
            assertEquals(HttpStatusCode.Unauthorized, devices(null).status)
            assertEquals(HttpStatusCode.Unauthorized, devices("not-a-token").status)
            assertEquals(HttpStatusCode.Unauthorized, devices(token.dropLast(1) + "A").status)
            val twice =
                client.get(DEVICES_PATH) {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    header(HttpHeaders.Authorization, "Bearer $token")
                }
            assertEquals(HttpStatusCode.Unauthorized, twice.status)
            assertEquals(HttpStatusCode.Unauthorized, client.get("/no-such-path").status, "unknown paths too")
            assertEquals(HttpStatusCode.OK, devices(token).status)
        }

    /** #68 SEC-2: someone else's failed logins don't spend a device's budget; successes do. */
    @Test
    fun onlySuccessfulLoginsSpendTheDeviceBudget() =
        authTest(limits = AuthLimits(clock, deviceLogins = KeyedRateLimiter(clock, capacity = 2, refillEvery = Duration.ofHours(1)))) {
            val device = TestDevice()
            val id = register(device, "mia").json<SessionResponse>().deviceId
            repeat(10) { assertEquals(HttpStatusCode.Unauthorized, login(TestDevice(), id).status) }

            assertEquals(HttpStatusCode.OK, login(device, id).status)
            assertEquals(HttpStatusCode.OK, login(device, id).status)
            assertEquals(HttpStatusCode.TooManyRequests, login(device, id).status)
        }

    /** #71 SEC-30: logins for random device ids create no buckets, so they can't crowd out real devices. */
    @Test
    fun randomDeviceIdsCannotFillTheLimiter() =
        authTest(
            limits =
                AuthLimits(
                    clock,
                    // The test clock is frozen: the global buckets need room for the whole flood.
                    challenges = KeyedRateLimiter(clock, capacity = 1_000, refillEvery = Duration.ofSeconds(1)),
                    authAttempts = KeyedRateLimiter(clock, capacity = 1_000, refillEvery = Duration.ofSeconds(1)),
                    deviceLogins = KeyedRateLimiter(clock, capacity = 5, refillEvery = Duration.ofHours(1), maxKeys = 2),
                ),
        ) {
            val device = TestDevice()
            val id = register(device, "noah").json<SessionResponse>().deviceId
            repeat(200) { login(TestDevice(), UUID.randomUUID().toString()) }

            assertEquals(HttpStatusCode.OK, login(device, id).status)
        }

    /** SEC-M0-5: the global buckets don't care about forwarded addresses. */
    @Test
    fun globalLimitsIgnoreForwardedAddresses() =
        authTest(limits = AuthLimits(clock, challenges = KeyedRateLimiter(clock, capacity = 3, refillEvery = Duration.ofHours(1)))) {
            repeat(3) { i -> client.post(CHALLENGE_PATH) { header("X-Forwarded-For", "10.0.0.$i") } }
            val limited = client.post(CHALLENGE_PATH) { header("X-Forwarded-For", "10.9.9.9") }

            assertEquals(HttpStatusCode.TooManyRequests, limited.status)
            assertEquals("""{"error":"rate_limited"}""", limited.bodyAsText())
        }

    @Test
    fun malformedInputIsAClosedBadRequest() =
        authTest {
            assertEquals(HttpStatusCode.BadRequest, postJson(LOGIN_PATH, """{"deviceId":"x","challenge":"!!","signature":"??"}""").status)
            assertEquals(HttpStatusCode.BadRequest, postJson(REGISTER_PATH, """{"username":"alice"}""").status)
        }

    @Test
    fun logsNeverContainTokensUsernamesOrChallenges() =
        captureLogs { logs ->
            authTest {
                val device = TestDevice()
                val session = register(device, "olivia_logs").json<SessionResponse>()
                val challenge = challenge()
                login(device, session.deviceId, challenge)
                register(TestDevice(), "olivia_logs", invite = UNKNOWN_CODE)
                devices(session.sessionToken)

                val text = logs.allText()
                assertFalse(text.contains(session.sessionToken), "token")
                assertFalse(text.contains("olivia_logs"), "username")
                assertFalse(text.contains(b64(challenge)), "challenge")
            }
        }

    private suspend inline fun <reified T> HttpResponse.json(): T = ProtocolJson.decodeFromString(bodyAsText())

    private companion object {
        // Well-formed codes (22 chars of canonical base64url, ADR-0010); the fake accepts only the ones it was given.
        val INVITE = InviteCodes.generate(java.security.SecureRandom())
        val SECOND = InviteCodes.generate(java.security.SecureRandom())
        val SPARE = InviteCodes.generate(java.security.SecureRandom())
        val UNKNOWN_CODE = InviteCodes.generate(java.security.SecureRandom())

        fun b64(bytes: ByteArray) = Base64Url.encode(bytes)
    }
}
