package app.gifitup.shared.data.network

import app.gifitup.protocol.ClaimKeyPackagesRequest
import app.gifitup.protocol.ProtocolJson
import app.gifitup.protocol.UploadKeyPackagesRequest
import app.gifitup.shared.data.network.FakeAuthServer.Companion.DEVICE_ID
import app.gifitup.shared.data.network.FakeAuthServer.Companion.dropped
import app.gifitup.shared.data.network.FakeAuthServer.Companion.error
import app.gifitup.shared.data.network.FakeAuthServer.Companion.json
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

/** The KeyPackage endpoints of [GifItUpApi] (ADR-0012). */
class KeyPackageApiTest {
    private val server = FakeAuthServer()
    private val api = server.api()
    private val other = "11111111-2222-4333-8444-555555555555"

    private suspend fun session(): Session = assertIs<ApiResult.Ok<Session>>(api.login(DEVICE_ID, ByteArray(56), ByteArray(64))).value

    private fun claimed(vararg devices: String) = json("""{"devices":[${devices.joinToString(",")}]}""")

    private fun device(
        id: String,
        keyPackage: String?,
    ) = """{"deviceId":"$id","keyPackage":${keyPackage?.let { "\"$it\"" }}}"""

    @Test
    fun uploadSendsBase64UrlWithTheBearerAndReadsTheAnswer() =
        runTest {
            val session = session()
            server.on("/key-packages", json("""{"accepted":2,"available":7,"lowWaterMark":20}"""))
            val packages = listOf(byteArrayOf(1, 2, -1), byteArrayOf(-5, 0))
            val result = assertIs<ApiResult.Ok<KeyPackageUpload>>(api.uploadKeyPackages(session, packages)).value
            assertEquals(KeyPackageUpload(2, 7, 20), result)

            val request = server.requests.last()
            assertEquals(HttpMethod.Post, request.data.method)
            assertEquals("Bearer ${FakeAuthServer.TOKEN}", request.data.headers["Authorization"])
            val sent = ProtocolJson.decodeFromString(UploadKeyPackagesRequest.serializer(), request.body)
            assertEquals(packages.map(Base64Url::encode), sent.keyPackages)
        }

    @Test
    fun statusReadsTheCounts() =
        runTest {
            server.on("/key-packages/status", json("""{"available":3,"lowWaterMark":20}"""))
            assertEquals(KeyPackageStatus(3, 20), assertIs<ApiResult.Ok<KeyPackageStatus>>(api.keyPackageStatus(session())).value)
            assertEquals(
                HttpMethod.Get,
                server.requests
                    .last()
                    .data.method,
            )
        }

    @Test
    fun claimSendsTheUsernameAndSuitesAndReturnsBytesOrNull() =
        runTest {
            val session = session()
            val bytes = byteArrayOf(9, 8, 7)
            server.on("/key-packages/claim", claimed(device(DEVICE_ID, Base64Url.encode(bytes)), device(other, null)))
            val result = assertIs<ApiResult.Ok<List<ClaimedKeyPackage>>>(api.claimKeyPackages(session, "ben", listOf(1, 2))).value
            assertEquals(listOf(DEVICE_ID, other), result.map { it.deviceId })
            assertContentEquals(bytes, result[0].keyPackage)
            assertNull(result[1].keyPackage)
            val sent = ProtocolJson.decodeFromString(ClaimKeyPackagesRequest.serializer(), server.requests.last().body)
            assertEquals(ClaimKeyPackagesRequest("ben", listOf(1, 2)), sent)
        }

    @Test
    fun aClaimIsNeverRepeated() =
        runTest {
            val session = session()
            server.on("/key-packages/claim", dropped, error(HttpStatusCode.InternalServerError, "internal"))
            assertEquals(ApiResult.Failed(ApiError.NETWORK), api.claimKeyPackages(session, "ben", listOf(1)))
            assertEquals(1, server.bodiesFor("/key-packages/claim").size, "a lost answer is not asked again by the client")
        }

    @Test
    fun answersOutsideTheProtocolAreRefused() =
        runTest {
            val session = session()
            val good = Base64Url.encode(byteArrayOf(1, 2, 3))
            val tooBig = Base64Url.encode(ByteArray(2049))
            val malformedClaims =
                listOf(
                    claimed(device(DEVICE_ID, good), device(DEVICE_ID, null)), // the same device twice
                    claimed(device("not-a-uuid", good)),
                    claimed(device(DEVICE_ID.uppercase(), good)),
                    claimed(device(DEVICE_ID, "$good=")), // padded
                    claimed(device(DEVICE_ID, "AB")), // non-zero trailing bits
                    claimed(device(DEVICE_ID, "")),
                    claimed(device(DEVICE_ID, tooBig)),
                    claimed(*Array(11) { device("00000000-0000-4000-8000-" + it.toString().padStart(12, '0'), good) }), // more than 10 devices
                    json("""{"devices":[{"deviceId":"$DEVICE_ID"}]}"""),
                    json("""{"devices":"x"}"""),
                )
            malformedClaims.forEach {
                server.on("/key-packages/claim", it)
                assertEquals(ApiResult.Failed(ApiError.MALFORMED_RESPONSE), api.claimKeyPackages(session, "ben", listOf(1)))
            }
            listOf(
                """{"accepted":1,"available":1,"lowWaterMark":20}""", // not the 2 sent
                """{"accepted":2,"available":1,"lowWaterMark":20}""", // fewer available than accepted
                """{"accepted":2,"available":5,"lowWaterMark":-1}""",
                """{"accepted":2,"available":5}""",
            ).forEach {
                server.on("/key-packages", json(it))
                assertEquals(
                    ApiResult.Failed(ApiError.MALFORMED_RESPONSE),
                    api.uploadKeyPackages(session, listOf(byteArrayOf(1), byteArrayOf(2))),
                )
            }
            listOf("""{"available":-1,"lowWaterMark":20}""", """{"available":1,"lowWaterMark":-3}""", """{"available":1}""").forEach {
                server.on("/key-packages/status", json(it))
                assertEquals(ApiResult.Failed(ApiError.MALFORMED_RESPONSE), api.keyPackageStatus(session))
            }
        }

    @Test
    fun badInputIsAProgrammingErrorBeforeAnythingIsSent() =
        runTest {
            val session = session()
            val before = server.requests.size
            listOf(
                emptyList(),
                List(51) { byteArrayOf(1) },
                listOf(ByteArray(0)),
                listOf(ByteArray(2049)),
            ).forEach { assertFailsWith<IllegalArgumentException> { api.uploadKeyPackages(session, it) } }
            listOf(
                "BEN" to listOf(1),
                "ab" to listOf(1),
                "ben" to emptyList(),
                "ben" to listOf(1, 1),
                "ben" to listOf(0),
                "ben" to listOf(65_536),
                "ben" to listOf(1, 2, 3, 4, 5),
            ).forEach { (name, suites) -> assertFailsWith<IllegalArgumentException> { api.claimKeyPackages(session, name, suites) } }
            assertEquals(before, server.requests.size)
        }

    @Test
    fun theLargestHonestClaimAnswerFitsTheBodyCap() =
        runTest {
            val session = session()
            val big = Base64Url.encode(ByteArray(2048) { it.toByte() })
            server.on(
                "/key-packages/claim",
                claimed(*Array(10) { device("00000000-0000-4000-8000-" + it.toString().padStart(12, '0'), big) }),
            )
            assertEquals(10, assertIs<ApiResult.Ok<List<ClaimedKeyPackage>>>(api.claimKeyPackages(session, "ben", listOf(1))).value.size)
        }
}
