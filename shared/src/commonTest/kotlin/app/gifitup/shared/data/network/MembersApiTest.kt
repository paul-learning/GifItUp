package app.gifitup.shared.data.network

import app.gifitup.shared.data.network.FakeAuthServer.Companion.DEVICE_ID
import app.gifitup.shared.data.network.FakeAuthServer.Companion.json
import io.ktor.http.HttpMethod
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** `GET /members` of [GifItUpApi] (ADR-0014): the whole answer is believed or refused. */
class MembersApiTest {
    private val server = FakeAuthServer()
    private val api = server.api()

    private suspend fun session(): Session = assertIs<ApiResult.Ok<Session>>(api.login(DEVICE_ID, ByteArray(56), ByteArray(64))).value

    private fun id(n: Int) = "00000000-0000-4000-8000-" + n.toString().padStart(12, '0')

    private fun key(n: Int) =
        Base64Url.encode(
            ByteArray(32) {
                if (it == 0) {
                    n.toByte()
                } else if (it == 1) {
                    (n shr 8).toByte()
                } else {
                    7
                }
            },
        )

    private fun device(
        n: Int,
        key: String = key(n),
        deviceId: String = id(100 + n),
    ) = """{"deviceId":"$deviceId","signatureKey":"$key"}"""

    private fun member(
        n: Int,
        username: String = "user$n",
        relation: String = "invitee",
        accountId: String = id(n),
        devices: List<String> = listOf(device(n)),
    ) = """{"accountId":"$accountId","username":"$username","relation":"$relation","devices":[${devices.joinToString(",")}]}"""

    private fun members(
        vararg members: String,
        more: Boolean = false,
    ) = json("""{"members":[${members.joinToString(",")}],"more":$more}""")

    private suspend fun answerFor(reply: Reply): ApiResult<MemberList> {
        val session = session()
        server.on("/members", reply)
        return api.listMembers(session)
    }

    @Test
    fun readsTheListAndSendsOnlyTheBearer() =
        runTest {
            val session = session()
            server.on("/members", members(member(1, relation = "inviter"), member(2, relation = "future"), more = true))
            val list = assertIs<ApiResult.Ok<MemberList>>(api.listMembers(session)).value
            assertEquals(true, list.more)
            assertEquals(listOf("user1", "user2"), list.members.map { it.username })
            assertEquals(listOf(MemberRelationship.INVITER, MemberRelationship.UNKNOWN), list.members.map { it.relationship })
            assertEquals(id(1), list.members[0].accountId)
            assertEquals(
                id(101),
                list.members[0]
                    .devices
                    .single()
                    .deviceId,
            )
            assertContentEquals(
                Base64Url.decode(key(1), 32..32),
                list.members[0]
                    .devices
                    .single()
                    .signatureKey,
            )

            val request = server.requests.last()
            assertEquals(HttpMethod.Get, request.data.method)
            assertEquals("Bearer ${FakeAuthServer.TOKEN}", request.data.headers["Authorization"])
            assertEquals("", request.body)
        }

    @Test
    fun anEmptyListIsAnswerToo() =
        runTest {
            val list = assertIs<ApiResult.Ok<MemberList>>(answerFor(members())).value
            assertEquals(emptyList(), list.members)
        }

    @Test
    fun anyViolationRefusesTheWholeAnswer() =
        runTest {
            val bad =
                mapOf(
                    "account id not a UUID" to members(member(1), member(2, accountId = "x")),
                    "upper case account id" to members(member(1, accountId = "ABCDEF01-2222-4333-8444-555555555555")),
                    "bad username" to members(member(1, username = "Ab")),
                    "two inviters" to members(member(1, relation = "inviter"), member(2, relation = "inviter")),
                    "same account twice" to members(member(1), member(2, accountId = id(1))),
                    "same username twice" to members(member(1), member(2, username = "user1")),
                    "same key twice" to members(member(1), member(2, devices = listOf(device(2, key = key(1))))),
                    "same device id twice" to members(member(1), member(2, devices = listOf(device(2, deviceId = id(101))))),
                    "key twice in one member" to members(member(1, devices = listOf(device(1), device(1, deviceId = id(300))))),
                    "padded key" to members(member(1, devices = listOf(device(1, key = key(1) + "=")))),
                    "short key" to members(member(1, devices = listOf(device(1, key = Base64Url.encode(ByteArray(31)))))),
                    "long key" to members(member(1, devices = listOf(device(1, key = Base64Url.encode(ByteArray(33)))))),
                    "trailing bits" to members(member(1, devices = listOf(device(1, key = key(1).dropLast(1) + "B")))),
                    "device id not a UUID" to members(member(1, devices = listOf(device(1, deviceId = "d")))),
                    "11 devices" to members(member(1, devices = List(11) { device(it + 1) })),
                    "201 members" to members(*Array(201) { member(it + 1, username = "user${it + 1}") }),
                    "missing more" to json("""{"members":[]}"""),
                    "missing devices" to
                        json("""{"members":[{"accountId":"${id(1)}","username":"user1","relation":"invitee"}],"more":false}"""),
                    "not JSON" to json("nope"),
                )
            bad.forEach { (name, reply) ->
                assertEquals(ApiResult.Failed(ApiError.MALFORMED_RESPONSE), answerFor(reply), name)
            }
        }

    @Test
    fun aBodyOverTheCapIsRefusedBeforeItIsRead() =
        runTest {
            val padded = json("""{"members":[],"more":false}""" + " ".repeat(GifItUpApi.MAX_MEMBERS_BODY_BYTES))
            assertEquals(ApiResult.Failed(ApiError.MALFORMED_RESPONSE), answerFor(padded))
        }

    @Test
    fun theLargestAllowedAnswerIsAccepted() =
        runTest {
            val big =
                members(
                    *Array(200) { m ->
                        member(
                            m + 1,
                            username = "user${m + 1}",
                            devices =
                                List(10) { d ->
                                    device(
                                        m * 10 + d + 1,
                                        deviceId =
                                            id(1000 + m * 10 + d),
                                    )
                                },
                        )
                    },
                )
            val list = assertIs<ApiResult.Ok<MemberList>>(answerFor(big)).value
            assertEquals(200, list.members.size)
            assertEquals(
                10,
                list.members
                    .last()
                    .devices.size,
            )
        }
}
