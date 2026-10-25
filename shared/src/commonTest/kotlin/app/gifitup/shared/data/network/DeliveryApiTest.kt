package app.gifitup.shared.data.network

import app.gifitup.shared.data.network.FakeAuthServer.Companion.DEVICE_ID
import app.gifitup.shared.data.network.FakeAuthServer.Companion.error
import app.gifitup.shared.data.network.FakeAuthServer.Companion.json
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Delivery Service calls of [GifItUpApi] (ADR-0015): requests as sent, answers believed whole or refused. */
class DeliveryApiTest {
    private val server = FakeAuthServer()
    private val api = server.api()
    private val group = ByteArray(16) { (it + 1).toByte() }
    private val groupText = Base64Url.encode(group)

    private suspend fun session(): Session = assertIs<ApiResult.Ok<Session>>(api.login(DEVICE_ID, ByteArray(56), ByteArray(64))).value

    private fun device(n: Int) = "00000000-0000-4000-8000-" + n.toString().padStart(12, '0')

    private fun application() =
        DeliveryEnvelope(ByteArray(16) { 9 }, MessageKind.APPLICATION, null, emptyList(), emptyList(), emptyMap(), byteArrayOf(1, 2, 3))

    @Suppress("LongParameterList") // One parameter per field of a message.
    private fun message(
        seq: Int,
        kind: String = "application",
        epoch: String = "null",
        sender: String = "\"${device(1)}\"",
        added: String = "[]",
        payload: String = "AQID",
    ) = """{"seq":$seq,"senderDeviceId":$sender,"kind":"$kind","epoch":$epoch,"added":$added,"removed":[],"payload":"$payload"}"""

    private fun page(
        vararg messages: String,
        oldest: Int = 1,
        last: Int = 5,
    ) = json("""{"oldestSeq":$oldest,"lastSeq":$last,"messages":[${messages.joinToString(",")}]}""")

    private suspend fun fetched(reply: Reply): ApiResult<MessagePage> {
        val session = session()
        server.on("/groups/$groupText/messages", reply)
        return api.fetchMessages(session, group, after = 0)
    }

    @Test
    fun aPostIsSentAsTheEnvelopeAndTheAnswerIsRead() =
        runTest {
            val session = session()
            server.on("/groups/$groupText/messages", json("""{"seq":7,"epoch":2}"""))
            val posted = assertIs<ApiResult.Ok<PostedMessage>>(api.postMessage(session, group, application())).value
            assertEquals(PostedMessage(7, 2), posted)
            val sent = server.requests.last()
            assertEquals(HttpMethod.Post, sent.data.method)
            assertEquals("Bearer ${FakeAuthServer.TOKEN}", sent.data.headers["Authorization"])
            assertTrue("\"kind\":\"application\"" in sent.body && "\"payload\":\"AQID\"" in sent.body, sent.body)
            assertTrue("=" !in sent.body, "base64url without padding")
        }

    @Test
    fun aCreateSendsTheGroupIdAndTheCommit() =
        runTest {
            val session = session()
            server.on("/groups", json("""{"seq":1,"epoch":1}"""))
            val commit =
                DeliveryEnvelope(
                    ByteArray(16),
                    MessageKind.COMMIT,
                    0,
                    listOf(device(2)),
                    emptyList(),
                    mapOf(device(2) to byteArrayOf(5)),
                    byteArrayOf(4),
                )
            assertEquals(PostedMessage(1, 1), assertIs<ApiResult.Ok<PostedMessage>>(api.createGroup(session, group, commit)).value)
            val body = server.requests.last().body
            assertTrue("\"groupId\":\"$groupText\"" in body && "\"welcomes\":[{\"deviceId\":\"${device(2)}\"" in body, body)
        }

    @Test
    fun theDeliveryErrorCodesMapToTheirOwnErrors() =
        runTest {
            val session = session()
            listOf(
                Triple(HttpStatusCode.Conflict, "epoch_conflict", ApiError.EPOCH_CONFLICT),
                Triple(HttpStatusCode.Conflict, "group_full", ApiError.GROUP_FULL),
                Triple(HttpStatusCode.Conflict, "welcome_limit", ApiError.WELCOME_LIMIT),
                Triple(HttpStatusCode.Conflict, "group_limit", ApiError.GROUP_LIMIT),
                Triple(HttpStatusCode.Conflict, "envelope_conflict", ApiError.ENVELOPE_CONFLICT),
                Triple(HttpStatusCode.BadRequest, "envelope_invalid", ApiError.ENVELOPE_INVALID),
                Triple(HttpStatusCode.PayloadTooLarge, "payload_too_large", ApiError.PAYLOAD_TOO_LARGE),
                Triple(HttpStatusCode.NotFound, "not_found", ApiError.NOT_FOUND),
            ).forEach { (status, code, expected) ->
                server.on("/groups/$groupText/messages", error(status, code))
                assertEquals(ApiResult.Failed(expected), api.postMessage(session, group, application()), code)
            }
        }

    @Test
    fun aPageIsReadAndAnythingOffRefusesTheWholeAnswer() =
        runTest {
            val ok = page(message(2), message(3, kind = "commit", epoch = "1", added = "[\"${device(5)}\"]"), message(4, sender = "null"))
            val read = assertIs<ApiResult.Ok<MessagePage>>(fetched(ok)).value
            assertEquals(listOf(2L, 3L, 4L), read.messages.map { it.seq })
            assertEquals(MessageKind.COMMIT, read.messages[1].kind)
            assertContentEquals(byteArrayOf(1, 2, 3), read.messages[0].payload)
            assertNull(read.messages[2].senderDeviceId)

            val broken =
                mapOf(
                    "out of order" to page(message(3), message(2)),
                    "a repeated seq" to page(message(2), message(2)),
                    "unknown kind" to page(message(2, kind = "other")),
                    "an application with an epoch" to page(message(2, epoch = "1")),
                    "a commit without an epoch" to page(message(2, kind = "commit")),
                    "an id that is not canonical" to page(message(2, sender = "\"NOT-A-UUID\"")),
                    "padding" to page(message(2, payload = "AQID=")),
                    "past the last seq" to page(message(9)),
                    "oldest beyond next" to page(message(2), oldest = 9),
                    "before the first retained seq" to page(message(2), oldest = 3),
                    "a commit that changes nothing" to page(message(2, kind = "commit", epoch = "1")),
                    "a device named twice" to page(message(2, kind = "commit", epoch = "1", added = "[\"${device(5)}\",\"${device(5)}\"]")),
                    "commit epochs going down" to
                        page(
                            message(2, kind = "commit", epoch = "3", added = "[\"${device(5)}\"]"),
                            message(3, kind = "commit", epoch = "2", added = "[\"${device(6)}\"]"),
                        ),
                )
            broken.forEach { (why, reply) -> assertEquals(ApiResult.Failed(ApiError.MALFORMED_RESPONSE), fetched(reply), why) }
        }

    @Test
    fun theFetchRequestCarriesTheCursorAndTheLimitInThePath() =
        runTest {
            fetched(page())
            val url =
                server.requests
                    .last()
                    .data.url
            assertEquals("/groups/$groupText/messages", url.encodedPath)
            assertEquals("0", url.parameters["after"])
            assertEquals("100", url.parameters["limit"])
        }

    @Test
    fun theStatusIsReadWithItsFreshnessPairWholeOrNotAtAll() =
        runTest {
            val session = session()
            val id = Base64Url.encode(ByteArray(16) { 3 })

            fun status(
                sent: String,
                msg: String,
            ) = json("""{"epoch":2,"oldestSeq":1,"lastSeq":5,"ackedSeq":3,"lastSentSeq":$sent,"lastClientMsgId":$msg}""")
            server.on("/groups/$groupText", status("4", "\"$id\""), status("null", "null"), status("4", "null"), status("4", "\"AAAA\""))
            val full = assertIs<ApiResult.Ok<GroupState>>(api.groupStatus(session, group)).value
            assertEquals(4L, full.lastSentSeq)
            assertContentEquals(ByteArray(16) { 3 }, full.lastClientMsgId)
            assertNull(assertIs<ApiResult.Ok<GroupState>>(api.groupStatus(session, group)).value.lastSentSeq)
            repeat(2) { assertEquals(ApiResult.Failed(ApiError.MALFORMED_RESPONSE), api.groupStatus(session, group)) }
        }

    @Test
    fun welcomesAreReadAndAckedByTheirId() =
        runTest {
            val session = session()
            val welcome = """{"id":"${device(8)}","groupId":"$groupText","welcome":"AQID"}"""
            server.on("/welcomes", json("""{"welcomes":[$welcome]}"""), json("""{"welcomes":[$welcome,$welcome]}"""))
            val read = assertIs<ApiResult.Ok<List<PendingWelcomeEntry>>>(api.pendingWelcomes(session)).value.single()
            assertEquals(device(8), read.id)
            assertContentEquals(group, read.groupId)
            assertEquals(ApiResult.Failed(ApiError.MALFORMED_RESPONSE), api.pendingWelcomes(session), "the same welcome twice")

            server.on("/welcomes/${device(8)}/ack", { _ -> respond("", HttpStatusCode.NoContent) })
            assertIs<ApiResult.Ok<Unit>>(api.ackWelcome(session, device(8)))
            server.on("/groups/$groupText/ack", { _ -> respond("", HttpStatusCode.NoContent) })
            assertIs<ApiResult.Ok<Unit>>(api.ackMessages(session, group, 3))
            assertNotNull(
                server.requests
                    .last()
                    .body
                    .takeIf { "\"seq\":3" in it },
            )
            server.on("/groups/$groupText/leave", { _ -> respond("", HttpStatusCode.NoContent) })
            assertIs<ApiResult.Ok<Unit>>(api.leaveGroup(session, group))
        }
}
