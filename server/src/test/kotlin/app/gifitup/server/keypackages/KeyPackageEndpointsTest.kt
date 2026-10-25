package app.gifitup.server.keypackages

import app.gifitup.keypackage.KeyPackageSpec
import app.gifitup.keypackage.buildKeyPackage
import app.gifitup.protocol.ApiErrorCode
import app.gifitup.protocol.ChallengeResponse
import app.gifitup.protocol.ClaimKeyPackagesRequest
import app.gifitup.protocol.ClaimKeyPackagesResponse
import app.gifitup.protocol.ErrorResponse
import app.gifitup.protocol.KeyPackageStatusResponse
import app.gifitup.protocol.PROTOCOL_VERSION
import app.gifitup.protocol.PROTOCOL_VERSION_HEADER
import app.gifitup.protocol.ProtocolJson
import app.gifitup.protocol.RegisterRequest
import app.gifitup.protocol.SessionResponse
import app.gifitup.protocol.UploadKeyPackagesRequest
import app.gifitup.protocol.UploadKeyPackagesResponse
import app.gifitup.server.AlwaysReady
import app.gifitup.server.allText
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
import app.gifitup.server.db.AccountRepository
import app.gifitup.server.db.Database
import app.gifitup.server.db.DatabaseConfig
import app.gifitup.server.db.InviteRepository
import app.gifitup.server.db.NewInvite
import app.gifitup.server.db.TestPostgres
import app.gifitup.server.db.TransactionRunner
import app.gifitup.server.invites.InviteService
import app.gifitup.server.module
import app.gifitup.server.noDatabaseDelivery
import app.gifitup.server.noDatabaseMembers
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
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.security.SecureRandom
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `POST /key-packages`, `GET /key-packages/status`, `POST /key-packages/claim` against the real database (ADR-0012). */
class KeyPackageEndpointsTest {
    private val clock = TestClock()
    private val origin = testConfig().publicOrigin
    private val counter = AtomicInteger()

    private inner class Member(
        val name: String,
        val token: String,
        val device: TestDevice,
    )

    private inner class Fixture(
        val db: Database,
        val client: HttpClient,
        val service: KeyPackageService,
        val limits: KeyPackageLimits,
    ) {
        private val repository = InviteRepository()

        private fun adminInvite(): String {
            val code = InviteCodes.generate(SecureRandom())
            db.transaction { c ->
                repository.insert(
                    c,
                    NewInvite(UUID.randomUUID(), checkNotNull(InviteCodes.hash(code)), null, clock.now, clock.now.plus(Duration.ofDays(1))),
                )
            }
            return code
        }

        suspend fun member(username: String): Member {
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
                                adminInvite(),
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

        /** A valid KeyPackage of [member]'s device; [tweak] changes the spec; every call makes different bytes. */
        fun keyPackage(
            member: Member,
            tweak: KeyPackageSpec.() -> Unit = {},
        ): ByteArray {
            val spec =
                KeyPackageSpec(
                    identity = member.name.encodeToByteArray(),
                    advertisedSignatureKey = member.device.publicKey,
                    leafSigner = member.device.pair,
                    keyPackageSigner = member.device.pair,
                    notBefore = clock.now.epochSecond - DAY,
                    notAfter = clock.now.epochSecond + LIFETIME_DAYS * DAY,
                    initKey = ByteArray(32) { (counter.incrementAndGet() + it).toByte() }.also { it[0] = counter.get().toByte() },
                    encryptionKey =
                        ByteArray(
                            32,
                        ) { (counter.get() * 7 + it + 99).toByte() }.also { it[1] = (counter.get() shr 8).toByte() },
                )
            spec.tweak()
            return buildKeyPackage(spec)
        }

        suspend fun upload(
            member: Member,
            items: List<String>,
        ): HttpResponse =
            client.post(KEY_PACKAGES_PATH) {
                bearerAuth(member.token)
                contentType(ContentType.Application.Json)
                setBody(ProtocolJson.encodeToString(UploadKeyPackagesRequest(items)))
            }

        suspend fun uploadBytes(
            member: Member,
            vararg packages: ByteArray,
        ): HttpResponse = upload(member, packages.map(Base64Url::encode))

        suspend fun status(member: Member): KeyPackageStatusResponse =
            ProtocolJson.decodeFromString(client.get("$KEY_PACKAGES_PATH/status") { bearerAuth(member.token) }.bodyAsText())

        suspend fun claimRaw(
            member: Member,
            body: String,
        ): HttpResponse =
            client.post("$KEY_PACKAGES_PATH/claim") {
                bearerAuth(member.token)
                contentType(ContentType.Application.Json)
                setBody(body)
            }

        suspend fun claim(
            member: Member,
            username: String,
            suites: List<Int> = listOf(1),
        ): HttpResponse = claimRaw(member, ProtocolJson.encodeToString(ClaimKeyPackagesRequest(username, suites)))

        suspend fun errorCode(response: HttpResponse): String =
            ProtocolJson.decodeFromString<ErrorResponse>(response.bodyAsText()).error.wire
    }

    private fun keyPackageTest(
        limits: KeyPackageLimits = KeyPackageLimits(clock),
        block: suspend Fixture.() -> Unit,
    ) {
        val url = TestPostgres.migratedDatabase()
        Database.connect(DatabaseConfig(url, APP_ROLE, TestPostgres.appPasswordFile())).use { db ->
            val auth = AuthService(db, origin, Challenges(clock), DbInviteRedeemer(), clock)
            val invites = InviteService(db, origin, clock)
            val service = KeyPackageService(db, clock, limits)
            testApplication {
                application { module(testConfig(), AlwaysReady, auth, invites, service, noDatabaseMembers(), noDatabaseDelivery()) }
                val client = createClient { defaultRequest { header(PROTOCOL_VERSION_HEADER, PROTOCOL_VERSION.toString()) } }
                Fixture(db, client, service, limits).block()
            }
        }
    }

    private fun roomy() =
        KeyPackageLimits(
            clock,
            uploads = KeyedRateLimiter(clock, 1_000, Duration.ofMinutes(1)),
            claimsByClaimer = KeyedRateLimiter(clock, 1_000, Duration.ofMinutes(1)),
            claimsByPair = KeyedRateLimiter(clock, 1_000, Duration.ofMinutes(1)),
            claimsByTarget = KeyedRateLimiter(clock, 1_000, Duration.ofMinutes(1)),
        )

    @Test
    fun aMemberUploadsAndAnotherClaimsExactlyThoseBytesOnce() =
        keyPackageTest {
            val ana = member("ana")
            val ben = member("ben")
            val kp = keyPackage(ben)
            val uploaded = upload(ben, listOf(Base64Url.encode(kp)))
            assertEquals(HttpStatusCode.OK, uploaded.status)
            val answer = ProtocolJson.decodeFromString<UploadKeyPackagesResponse>(uploaded.bodyAsText())
            assertEquals(UploadKeyPackagesResponse(1, 1, KeyPackageService.LOW_WATER_MARK), answer)
            assertEquals(1, status(ben).available)

            val claimed = claim(ana, "ben")
            assertEquals(HttpStatusCode.OK, claimed.status)
            assertEquals("no-store", claimed.headers["Cache-Control"])
            val claimedBody = ProtocolJson.decodeFromString<ClaimKeyPackagesResponse>(claimed.bodyAsText())
            val benId = db.transaction { c -> AccountRepository().findIdByUsername(c, "ben") }
            assertEquals(benId.toString(), claimedBody.accountId, "the claimed account's id (ADR-0014)")
            val device = claimedBody.devices.single()
            assertContentEquals(kp, Base64Url.decodeUpTo(assertNotNull(device.keyPackage), 2048))
            assertEquals(0, status(ben).available, "delete on claim")

            val again = ProtocolJson.decodeFromString<ClaimKeyPackagesResponse>(claim(ana, "ben").bodyAsText())
            assertEquals(device.deviceId, again.devices.single().deviceId)
            assertNull(again.devices.single().keyPackage, "an empty pool answers with the device and no KeyPackage")
        }

    @Test
    fun unknownAndOwnUsernameGiveTheSameAnswerAndCostOnlyAClaimerToken() =
        keyPackageTest {
            val ana = member("ana")
            val ben = member("ben")
            uploadBytes(ben, keyPackage(ben), keyPackage(ben))
            val unknown = claim(ana, "nobody")
            val own = claim(ana, "ana")
            assertEquals(HttpStatusCode.NotFound, unknown.status)
            assertEquals(HttpStatusCode.NotFound, own.status)
            assertEquals(unknown.bodyAsText(), own.bodyAsText())
            // Probing is limited: 20 misses in all (2 so far), then a 429, the same for both kinds.
            repeat(18) { assertEquals(HttpStatusCode.NotFound, claim(ana, if (it % 2 == 0) "nobody" else "ana").status) }
            assertEquals(HttpStatusCode.TooManyRequests, claim(ana, "nobody").status)
            assertEquals(HttpStatusCode.TooManyRequests, claim(ana, "ana").status)
            // Only the claimer's bucket was touched: after a refill the pair and target buckets are still full.
            clock.advance(Duration.ofMinutes(6))
            assertEquals(HttpStatusCode.OK, claim(ana, "ben").status)
            assertEquals(HttpStatusCode.OK, claim(ana, "ben").status)
        }

    @Test
    fun uploadsForAnotherIdentityOrKeyOrWithJunkAreRefusedWithOneAnswer() =
        keyPackageTest(roomy()) {
            val ana = member("ana")
            val ben = member("ben")
            val bad =
                listOf(
                    "identity of someone else" to Base64Url.encode(keyPackage(ana) { identity = "ben".encodeToByteArray() }),
                    "device key of someone else" to
                        Base64Url.encode(
                            keyPackage(ana) {
                                advertisedSignatureKey = ben.device.publicKey
                                leafSigner = ben.device.pair
                                keyPackageSigner = ben.device.pair
                            },
                        ),
                    "expires too soon" to Base64Url.encode(keyPackage(ana) { notAfter = clock.now.epochSecond + 3 * DAY }),
                    "not valid yet" to Base64Url.encode(keyPackage(ana) { notBefore = clock.now.epochSecond + 2 * 3600 }),
                    "last resort" to Base64Url.encode(keyPackage(ana) { keyPackageExtensions = listOf(0x000a to ByteArray(0)) }),
                    "padding" to Base64Url.encode(keyPackage(ana)) + "=",
                    "not base64" to "####",
                    "empty" to "",
                )
            val answers =
                bad.map { (why, text) ->
                    val response = upload(ana, listOf(text))
                    assertEquals(HttpStatusCode.BadRequest, response.status, why)
                    assertEquals(ApiErrorCode.KEY_PACKAGE_INVALID.wire, errorCode(response), why)
                    response.bodyAsText()
                }
            assertEquals(1, answers.toSet().size, "one answer for every reason")
            assertEquals(0, status(ana).available)
            val good = keyPackage(ana)
            // all or none: one bad item in a batch stores nothing.
            val mixed = upload(ana, listOf(Base64Url.encode(good), Base64Url.encode(keyPackage(ben))))
            assertEquals(HttpStatusCode.BadRequest, mixed.status)
            assertEquals(0, status(ana).available)
        }

    @Test
    fun theSameBytesTwiceAreRefusedAndTwoRacingIdenticalBatchesNeverGiveA500() =
        keyPackageTest(roomy()) {
            val ana = member("ana")
            val one = Base64Url.encode(keyPackage(ana))
            assertEquals(HttpStatusCode.BadRequest, upload(ana, listOf(one, one)).status, "twice in a batch")
            assertEquals(HttpStatusCode.OK, upload(ana, listOf(one)).status)
            assertEquals(HttpStatusCode.BadRequest, upload(ana, listOf(one)).status, "already stored")
            assertEquals(1, status(ana).available)

            val racing = List(2) { Base64Url.encode(keyPackage(ana)) }
            val outcomes = java.util.Collections.synchronizedList(mutableListOf<UploadOutcome>())
            val deviceId = db.transaction { c -> deviceIdOf(c, "ana") }
            val start = CountDownLatch(1)
            val threads =
                List(2) {
                    Thread {
                        start.await()
                        outcomes += service.upload(deviceId, racing)
                    }.apply { start() }
                }
            start.countDown()
            threads.forEach { it.join() }
            assertEquals(1, outcomes.count { it is UploadOutcome.Accepted })
            assertEquals(1, outcomes.count { it === UploadOutcome.Invalid })
        }

    @Test
    fun aDeviceHoldsAtMostTwoHundredUnexpiredKeyPackages() =
        keyPackageTest(roomy()) {
            val ana = member("ana")
            repeat(4) {
                assertEquals(HttpStatusCode.OK, upload(ana, List(KeyPackageService.MAX_BATCH) { Base64Url.encode(keyPackage(ana)) }).status)
            }
            assertEquals(KeyPackageService.MAX_UNEXPIRED, status(ana).available)
            val over = uploadBytes(ana, keyPackage(ana))
            assertEquals(HttpStatusCode.Conflict, over.status)
            assertEquals(ApiErrorCode.KEY_PACKAGE_LIMIT.wire, errorCode(over))
            assertEquals(KeyPackageService.MAX_UNEXPIRED, status(ana).available)
        }

    @Test
    fun uploadsAreRateLimitedPerDeviceAndFailedOnesSpendToo() =
        keyPackageTest {
            val ana = member("ana")
            repeat(5) { assertEquals(HttpStatusCode.BadRequest, upload(ana, listOf("####")).status) }
            val sixth = uploadBytes(ana, keyPackage(ana))
            assertEquals(HttpStatusCode.TooManyRequests, sixth.status)
            assertEquals(ApiErrorCode.RATE_LIMITED.wire, errorCode(sixth))
            clock.advance(Duration.ofMinutes(10))
            assertEquals(HttpStatusCode.OK, uploadBytes(ana, keyPackage(ana)).status)
        }

    @Test
    fun aPairOfAccountsMayClaimTwiceADayAndTheRefusalGivesTheOtherTokensBack() =
        keyPackageTest {
            val ana = member("ana")
            val ben = member("ben")
            uploadBytes(ben, keyPackage(ben), keyPackage(ben), keyPackage(ben))
            assertEquals(HttpStatusCode.OK, claim(ana, "ben").status)
            assertEquals(HttpStatusCode.OK, claim(ana, "ben").status)
            assertEquals(HttpStatusCode.TooManyRequests, claim(ana, "ben").status)
            assertEquals(1, status(ben).available, "a refused claim takes nothing")
            // Ana spent two claimer tokens, not three: the refused claim gave its claimer token back.
            val key = db.transaction { c -> accountIdOf(c, "ana") }.toString()
            repeat(18) { assertTrue(limits.claimsByClaimer.spend(key), "token ${it + 1} of the 18 left") }
            assertFalse(limits.claimsByClaimer.spend(key))
        }

    @Test
    fun manyParallelClaimsOfOnePairSucceedAtMostTwice() =
        keyPackageTest {
            val ana = member("ana")
            val ben = member("ben")
            ben.let { b -> uploadBytes(b, *Array(10) { keyPackage(b) }) }
            val anaId = db.transaction { c -> accountIdOf(c, "ana") }
            val start = CountDownLatch(1)
            val won = AtomicInteger()
            val threads =
                List(100) {
                    Thread {
                        start.await()
                        val outcome = service.claim(anaId, "ben", listOf(1))
                        if (outcome is ClaimOutcome.Claimed) won.incrementAndGet()
                    }.apply { start() }
                }
            start.countDown()
            threads.forEach { it.join() }
            assertEquals(2, won.get())
            assertEquals(8, status(ben).available, "exactly the two claimed ones are gone")
        }

    @Test
    fun aClaimSkipsKeyPackagesThatExpireWithinADay() =
        keyPackageTest {
            val ana = member("ana")
            val ben = member("ben")
            val soon = keyPackage(ben) { notAfter = clock.now.epochSecond + 8 * DAY }
            val later = keyPackage(ben) { notAfter = clock.now.epochSecond + 40 * DAY }
            assertEquals(HttpStatusCode.OK, uploadBytes(ben, soon, later).status)
            clock.advance(Duration.ofHours(7 * 24 + 12)) // `soon` has half a day left, `later` still 32 days
            val anaId = db.transaction { c -> accountIdOf(c, "ana") }
            val first = (service.claim(anaId, "ben", listOf(1)) as ClaimOutcome.Claimed).devices.single().keyPackage
            assertContentEquals(later, first, "the nearly dead one is skipped")
            val second = (service.claim(anaId, "ben", listOf(1)) as ClaimOutcome.Claimed).devices.single().keyPackage
            assertNull(second, "and stays unclaimable")
            assertNotNull(ana)
        }

    @Test
    fun aFailedClaimGivesEveryTokenBackAndTakesNothing() =
        keyPackageTest {
            val ana = member("ana")
            val ben = member("ben")
            uploadBytes(ben, keyPackage(ben))
            val calls = AtomicInteger()
            val flaky =
                object : TransactionRunner {
                    override fun <T> transaction(block: (java.sql.Connection) -> T): T {
                        // The first call is the username lookup; the claim itself (the second) fails.
                        check(calls.incrementAndGet() != 2) { "database went away" }
                        return db.transaction(block)
                    }
                }
            val failing = KeyPackageService(flaky, clock, limits)
            val anaId = db.transaction { c -> accountIdOf(c, "ana") }
            assertFailsWith<IllegalStateException> { failing.claim(anaId, "ben", listOf(1)) }
            assertEquals(1, status(ben).available)
            // Pair burst is 2 and nothing was kept: two claims still work, the third does not.
            assertEquals(HttpStatusCode.OK, claim(ana, "ben").status)
            assertEquals(HttpStatusCode.OK, claim(ana, "ben").status)
            assertEquals(HttpStatusCode.TooManyRequests, claim(ana, "ben").status)
        }

    @Test
    fun malformedClaimsAreRefusedBeforeAnyLimitIsSpent() =
        keyPackageTest {
            val ana = member("ana")
            val ben = member("ben")
            uploadBytes(ben, keyPackage(ben))
            val bodies =
                listOf(
                    """{"username":"BEN!","ciphersuites":[1]}""",
                    """{"username":"ben","ciphersuites":[]}""",
                    """{"username":"ben","ciphersuites":[1,1]}""",
                    """{"username":"ben","ciphersuites":[1,2,3,4,5]}""",
                    """{"username":"ben","ciphersuites":[0]}""",
                    """{"username":"ben","ciphersuites":[65536]}""",
                )
            bodies.forEach { assertEquals(HttpStatusCode.BadRequest, claimRaw(ana, it).status, it) }
            assertEquals(HttpStatusCode.OK, claim(ana, "ben").status)
            assertEquals(HttpStatusCode.OK, claim(ana, "ben").status, "both pair tokens were still there")
        }

    @Test
    fun aClaimWithAnUnsupportedSuiteGetsNoKeyPackageButTheDevice() =
        keyPackageTest {
            val ana = member("ana")
            val ben = member("ben")
            uploadBytes(ben, keyPackage(ben))
            val response = ProtocolJson.decodeFromString<ClaimKeyPackagesResponse>(claim(ana, "ben", listOf(2)).bodyAsText())
            assertNull(response.devices.single().keyPackage)
            assertEquals(1, status(ben).available, "nothing taken")
        }

    @Test
    fun batchSizesAndBodyLimitsAreEnforcedPerRoute() =
        keyPackageTest(roomy()) {
            val ana = member("ana")
            assertEquals(HttpStatusCode.BadRequest, upload(ana, emptyList()).status)
            val tooMany = List(KeyPackageService.MAX_BATCH + 1) { Base64Url.encode(keyPackage(ana)) }
            assertEquals(HttpStatusCode.BadRequest, upload(ana, tooMany).status)
            assertEquals(0, status(ana).available)

            // 50 items of the maximum 2,731 characters: the largest honest request is far below the route's limit.
            val full = List(KeyPackageService.MAX_BATCH) { it.toString(16).padStart(2, 'A') + "A".repeat(2729) }
            val fullBody = ProtocolJson.encodeToString(UploadKeyPackagesRequest(full))
            assertTrue(fullBody.length < UPLOAD_BODY_LIMIT_BYTES)
            assertEquals(HttpStatusCode.BadRequest, upload(ana, full).status, "within the limit: refused by the reader, not by size")

            // Exactly at the limit is read (and refused as invalid); one byte more is 413.
            val frame = ProtocolJson.encodeToString(UploadKeyPackagesRequest(listOf(""))).length

            fun sized(total: Long) = ProtocolJson.encodeToString(UploadKeyPackagesRequest(listOf("A".repeat((total - frame).toInt()))))

            suspend fun send(body: String) =
                client.post(KEY_PACKAGES_PATH) {
                    bearerAuth(ana.token)
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            assertEquals(HttpStatusCode.BadRequest, send(sized(UPLOAD_BODY_LIMIT_BYTES)).status)
            assertEquals(HttpStatusCode.PayloadTooLarge, send(sized(UPLOAD_BODY_LIMIT_BYTES + 1)).status)
            val huge = sized(UPLOAD_BODY_LIMIT_BYTES + 1)

            val unauthenticated =
                client.post(KEY_PACKAGES_PATH) {
                    contentType(ContentType.Application.Json)
                    setBody(huge)
                }
            assertEquals(HttpStatusCode.Unauthorized, unauthenticated.status)

            // The larger limit is for this route only: the claim route keeps the default.
            val bigClaim =
                client.post("$KEY_PACKAGES_PATH/claim") {
                    bearerAuth(ana.token)
                    contentType(ContentType.Application.Json)
                    setBody("""{"username":"${"a".repeat(DEFAULT_LIMIT_PLUS)}","ciphersuites":[1]}""")
                }
            assertEquals(HttpStatusCode.PayloadTooLarge, bigClaim.status)
        }

    @Test
    fun theDirectoryNeedsASessionAndLogsCarryNoIdsOrBytes() =
        captureLogs { logs ->
            keyPackageTest(roomy()) {
                assertEquals(HttpStatusCode.Unauthorized, client.get("$KEY_PACKAGES_PATH/status").status)
                assertEquals(HttpStatusCode.Unauthorized, client.post(KEY_PACKAGES_PATH).status)
                assertEquals(HttpStatusCode.Unauthorized, client.post("$KEY_PACKAGES_PATH/claim").status)

                val ana = member("ana")
                val ben = member("ben")
                val kp = Base64Url.encode(keyPackage(ben))
                upload(ben, listOf(kp))
                upload(ben, listOf("####"))
                val claimed = claim(ana, "ben")
                claim(ana, "nobody")
                val deviceId =
                    ProtocolJson
                        .decodeFromString<ClaimKeyPackagesResponse>(claimed.bodyAsText())
                        .devices
                        .single()
                        .deviceId
                val text = logs.allText()
                assertTrue(text.contains("event=key_package_upload result=ok"))
                assertTrue(text.contains("event=key_package_upload result=invalid reason=ENCODING"))
                assertTrue(text.contains("event=key_package_claim result=ok"))
                assertTrue(text.contains("event=key_package_claim result=not_found"))
                assertFalse(text.contains(kp.take(40)), "no KeyPackage bytes")
                assertFalse(text.contains(deviceId), "no device id")
                assertFalse(text.contains("ben"), "no usernames")
            }
        }

    @Test
    fun aClaimedKeyPackageIsDeletedNotMarked() =
        keyPackageTest {
            val ana = member("ana")
            val ben = member("ben")
            uploadBytes(ben, keyPackage(ben))
            claim(ana, "ben")
            val rows =
                db.transaction { c ->
                    c.prepareStatement("SELECT count(*) FROM gifitup.key_packages").use { s ->
                        s.executeQuery().use { it.next().let { _ -> it.getInt(1) } }
                    }
                }
            assertEquals(0, rows)
        }

    private fun deviceIdOf(
        c: java.sql.Connection,
        username: String,
    ): UUID =
        c.prepareStatement("SELECT d.id FROM gifitup.devices d JOIN gifitup.accounts a ON a.id = d.account_id WHERE a.username = ?").use {
            it.setString(1, username)
            it.executeQuery().use { rows ->
                check(rows.next())
                rows.getObject(1, UUID::class.java)
            }
        }

    private fun accountIdOf(
        c: java.sql.Connection,
        username: String,
    ): UUID =
        c.prepareStatement("SELECT id FROM gifitup.accounts WHERE username = ?").use {
            it.setString(1, username)
            it.executeQuery().use { rows ->
                check(rows.next())
                rows.getObject(1, UUID::class.java)
            }
        }

    private companion object {
        const val DAY = 24L * 3600
        const val LIFETIME_DAYS = 60L
        const val DEFAULT_LIMIT_PLUS = 70_000
    }
}
