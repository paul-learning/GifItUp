package app.gifitup.server.delivery

import app.gifitup.protocol.ChallengeResponse
import app.gifitup.protocol.CreateGroupRequest
import app.gifitup.protocol.DeliveryKinds
import app.gifitup.protocol.ErrorResponse
import app.gifitup.protocol.FetchMessagesResponse
import app.gifitup.protocol.GroupStatusResponse
import app.gifitup.protocol.PROTOCOL_VERSION
import app.gifitup.protocol.PROTOCOL_VERSION_HEADER
import app.gifitup.protocol.PendingWelcomesResponse
import app.gifitup.protocol.PostMessageRequest
import app.gifitup.protocol.PostMessageResponse
import app.gifitup.protocol.ProtocolJson
import app.gifitup.protocol.RegisterRequest
import app.gifitup.protocol.SessionResponse
import app.gifitup.protocol.WelcomeItem
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
import kotlin.test.assertEquals

/** A registered member with a session and one device. */
internal class Member(
    val name: String,
    val token: String,
    val deviceId: String,
)

/** The running server on a fresh database, with helpers for the Delivery Service endpoints (ADR-0015). */
internal class DeliveryFixture(
    val db: Database,
    val client: HttpClient,
    val clock: TestClock,
    val service: DeliveryService,
) {
    private val random = SecureRandom()
    private val repository = InviteRepository()
    private val origin = testConfig().publicOrigin

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
        val session = ProtocolJson.decodeFromString<SessionResponse>(response.bodyAsText())
        return Member(username, session.sessionToken, session.deviceId)
    }

    private fun adminInvite(): String {
        val code = InviteCodes.generate(random)
        db.transaction { c ->
            repository.insert(
                c,
                NewInvite(UUID.randomUUID(), checkNotNull(InviteCodes.hash(code)), null, clock.now, clock.now.plus(Duration.ofDays(1))),
            )
        }
        return code
    }

    fun newGroupId(): String = Base64Url.encode(ByteArray(GROUP_ID_BYTES).also(random::nextBytes))

    fun msgId(): String = Base64Url.encode(ByteArray(MSG_ID_BYTES).also(random::nextBytes))

    fun bytes(
        size: Int,
        fill: Int = 1,
    ): String = Base64Url.encode(ByteArray(size) { fill.toByte() })

    fun application(
        payload: String = bytes(PAYLOAD_BYTES),
        clientMsgId: String = msgId(),
    ) = PostMessageRequest(clientMsgId, DeliveryKinds.APPLICATION, null, emptyList(), emptyList(), emptyList(), payload)

    fun commit(
        epoch: Long,
        added: List<Member> = emptyList(),
        removed: List<String> = emptyList(),
        clientMsgId: String = msgId(),
        payload: String = bytes(PAYLOAD_BYTES, 2),
    ) = PostMessageRequest(
        clientMsgId,
        DeliveryKinds.COMMIT,
        epoch,
        added.map { it.deviceId },
        removed,
        added.map { WelcomeItem(it.deviceId, bytes(WELCOME_BYTES, 3)) },
        payload,
    )

    suspend fun create(
        member: Member,
        groupId: String,
        commit: PostMessageRequest,
    ): HttpResponse = json(member, "/groups", ProtocolJson.encodeToString(CreateGroupRequest(groupId, commit)))

    suspend fun post(
        member: Member,
        groupId: String,
        request: PostMessageRequest,
    ): HttpResponse = json(member, "/groups/$groupId/messages", ProtocolJson.encodeToString(request))

    suspend fun json(
        member: Member,
        path: String,
        body: String,
    ): HttpResponse =
        client.post(path) {
            bearerAuth(member.token)
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    suspend fun get(
        member: Member,
        path: String,
    ): HttpResponse = client.get(path) { bearerAuth(member.token) }

    suspend fun fetch(
        member: Member,
        groupId: String,
        after: Long = 0,
    ): FetchMessagesResponse =
        ProtocolJson.decodeFromString(
            get(member, "/groups/$groupId/messages?after=$after")
                .also {
                    assertEquals(HttpStatusCode.OK, it.status)
                }.bodyAsText(),
        )

    suspend fun status(
        member: Member,
        groupId: String,
    ): GroupStatusResponse =
        ProtocolJson.decodeFromString(get(member, "/groups/$groupId").also { assertEquals(HttpStatusCode.OK, it.status) }.bodyAsText())

    suspend fun welcomes(member: Member): PendingWelcomesResponse =
        ProtocolJson.decodeFromString(get(member, "/welcomes").also { assertEquals(HttpStatusCode.OK, it.status) }.bodyAsText())

    suspend fun posted(response: HttpResponse): PostMessageResponse {
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return ProtocolJson.decodeFromString(response.bodyAsText())
    }

    suspend fun errorCode(response: HttpResponse): String = ProtocolJson.decodeFromString<ErrorResponse>(response.bodyAsText()).error.wire

    private companion object {
        const val GROUP_ID_BYTES = 16
        const val MSG_ID_BYTES = 16
        const val PAYLOAD_BYTES = 100
        const val WELCOME_BYTES = 200
    }
}

/** Runs [block] against the real server and database with the given Delivery Service. */
internal fun deliveryTest(
    clock: TestClock = TestClock(),
    limits: (TestClock) -> DeliveryLimits = { DeliveryLimits(it) },
    rules: DeliveryRules = DeliveryRules(),
    block: suspend DeliveryFixture.() -> Unit,
) {
    val url = TestPostgres.migratedDatabase()
    val origin = testConfig().publicOrigin
    Database.connect(DatabaseConfig(url, APP_ROLE, TestPostgres.appPasswordFile())).use { db ->
        val auth = AuthService(db, origin, Challenges(clock), DbInviteRedeemer(), clock)
        val service = DeliveryService(db, clock, limits(clock), rules)
        testApplication {
            application {
                module(
                    testConfig(),
                    AlwaysReady,
                    auth,
                    InviteService(db, origin, clock),
                    noDatabaseKeyPackages(),
                    noDatabaseMembers(),
                    service,
                )
            }
            val client = createClient { defaultRequest { header(PROTOCOL_VERSION_HEADER, PROTOCOL_VERSION.toString()) } }
            DeliveryFixture(db, client, clock, service).block()
        }
    }
}
