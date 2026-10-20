package app.gifitup.shared.data.network

import app.gifitup.protocol.LoginRequest
import app.gifitup.protocol.ProtocolJson
import app.gifitup.protocol.RegisterRequest
import app.gifitup.shared.data.network.FakeAuthServer.Companion.DEVICE_ID
import app.gifitup.shared.data.network.FakeAuthServer.Companion.dropped
import app.gifitup.shared.data.network.FakeAuthServer.Companion.error
import app.gifitup.shared.data.network.FakeAuthServer.Companion.json
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GifItUpApiTest {
    private val server = FakeAuthServer()
    private val api = server.api()

    @Test
    fun everyRequestGoesToTheOriginWithTheProtocolHeader() =
        runTest {
            api.challenge()
            val session = assertIs<ApiResult.Ok<Session>>(api.login(DEVICE_ID, ByteArray(56), ByteArray(64))).value
            api.devices(session)
            api.logout(session)

            assertEquals(4, server.requests.size)
            server.requests.forEach {
                assertEquals("https://gifitup.test${it.path}", it.data.url.toString())
                assertEquals(listOf("1"), it.data.headers.getAll("GifItUp-Protocol"))
            }
            val bearer =
                server.requests
                    .filter {
                        it.path in
                            setOf(
                                "/devices",
                                "/auth/logout",
                            )
                    }.map { it.data.headers[HttpHeaders.Authorization] }
            assertEquals(List(2) { "Bearer ${FakeAuthServer.TOKEN}" }, bearer)
            assertNull(
                server.requests
                    .first()
                    .data.headers[HttpHeaders.Authorization],
            )
        }

    @OptIn(DevOnlyEndpoint::class)
    @Test
    fun loopbackEndpointSendsToTheLiteralAddressButKeepsTheOrigin() =
        runTest {
            val loopback = assertNotNull(ApiEndpoint.loopback("https://e2e.invalid", 8080))
            server.api(loopback).challenge()
            assertEquals(
                "http://127.0.0.1:8080/auth/challenge",
                server.requests
                    .single()
                    .data.url
                    .toString(),
            )
            assertEquals("https://e2e.invalid", loopback.origin)
            assertEquals("http://[::1]:9", ApiEndpoint.loopback("https://e2e.invalid", 9, ipv6 = true)?.baseUrl)
        }

    @OptIn(DevOnlyEndpoint::class)
    @Test
    fun onlyCanonicalOriginsAreAccepted() {
        val good = listOf("https://gifitup.app", "https://a.b-c.d:8443", "https://x:1", "https://x:65535")
        val bad =
            listOf(
                "http://gifitup.app",
                "https://GifItUp.app",
                "https://gifitup.app/",
                "https://gifitup.app:443",
                "https://gifitup.app:08443",
                "https://gifitup.app:65536",
                "https://-a.app",
                "https://a..app",
                "https://user@gifitup.app",
                "https://gifitup.app?x",
                "https://" + "a".repeat(250),
            )
        good.forEach { assertNotNull(ApiEndpoint.forOrigin(it), it) }
        bad.forEach { assertNull(ApiEndpoint.forOrigin(it), it) }
        assertNull(ApiEndpoint.loopback("http://e2e.invalid", 8080))
        assertNull(ApiEndpoint.loopback("https://e2e.invalid", 0))
        assertNull(ApiEndpoint.loopback("https://e2e.invalid", 65_536))
    }

    @Test
    fun serverErrorsMapToClosedCodes() =
        runTest {
            val cases =
                listOf(
                    error(HttpStatusCode.BadRequest, "bad_request") to ApiError.BAD_REQUEST,
                    json("""{"error":"client_outdated","minProtocolVersion":7}""", HttpStatusCode.BadRequest) to ApiError.CLIENT_OUTDATED,
                    error(HttpStatusCode.BadRequest, "server_outdated") to ApiError.SERVER_OUTDATED,
                    error(HttpStatusCode.Unauthorized, "unauthorized") to ApiError.UNAUTHORIZED,
                    error(HttpStatusCode.Forbidden, "invite_invalid") to ApiError.INVITE_INVALID,
                    error(HttpStatusCode.Conflict, "username_unavailable") to ApiError.USERNAME_UNAVAILABLE,
                    error(HttpStatusCode.TooManyRequests, "rate_limited") to ApiError.RATE_LIMITED,
                    error(HttpStatusCode.NotFound, "not_found") to ApiError.NOT_FOUND,
                    error(HttpStatusCode.BadRequest, "key_package_invalid") to ApiError.KEY_PACKAGE_INVALID,
                    error(HttpStatusCode.Conflict, "key_package_limit") to ApiError.KEY_PACKAGE_LIMIT,
                    error(HttpStatusCode.InternalServerError, "internal") to ApiError.SERVER_ERROR,
                    error(HttpStatusCode.Forbidden, "a_code_from_the_future") to ApiError.SERVER_ERROR,
                    json("<html>proxy error</html>", HttpStatusCode.BadGateway) to ApiError.MALFORMED_RESPONSE,
                    dropped to ApiError.NETWORK,
                    // Ktor's web fetch engine fails with kotlin.Error, not an Exception (#94 CR-2).
                    FakeAuthServer.fetchFailed to ApiError.NETWORK,
                )
            cases.forEach { (reply, expected) ->
                server.on("/auth/challenge", reply)
                assertEquals(ApiResult.Failed(expected), api.challenge())
            }
        }

    @Test
    fun aMalformedSuccessBodyNeverReachesTheResult() =
        runTest {
            val marker = "SECRET_TOKEN_MARKER"
            val bodies =
                listOf(
                    """{"deviceId":"$DEVICE_ID","sessionToken":"$marker","expiresAt":"$marker"}""",
                    """{"deviceId":"$marker","sessionToken":"${FakeAuthServer.TOKEN}","expiresAt":1}""",
                    """{"deviceId":"${DEVICE_ID.uppercase()}","sessionToken":"${FakeAuthServer.TOKEN}","expiresAt":1}""",
                    """{"deviceId":"$DEVICE_ID","sessionToken":"${FakeAuthServer.TOKEN}A","expiresAt":1}""",
                    """{"deviceId":"$DEVICE_ID","sessionToken":"${FakeAuthServer.TOKEN}","expiresAt":0}""",
                    """{"deviceId":"$DEVICE_ID","sessionToken":"$marker"}""",
                    "not json $marker",
                )
            bodies.forEach { body ->
                server.on("/auth/login", json(body))
                val result = api.login(DEVICE_ID, ByteArray(56), ByteArray(64))
                assertEquals(ApiResult.Failed(ApiError.MALFORMED_RESPONSE), result, body)
                assertFalse(marker in result.toString())
            }
            // A success status other than the endpoint's own is outside the protocol too.
            server.on("/accounts", json(FakeAuthServer.session(DEVICE_ID), HttpStatusCode.OK))
            assertEquals(
                ApiResult.Failed(ApiError.MALFORMED_RESPONSE),
                api.register("ann", "x", ByteArray(1), ByteArray(56), ByteArray(64)),
            )
        }

    @Test
    fun challengesMustBeCanonicalAndSixteenToSixtyFourBytes() =
        runTest {
            fun challenge(text: String) = json("""{"challenge":"$text"}""")
            listOf(ByteArray(15), ByteArray(65)).forEach {
                server.on("/auth/challenge", challenge(Base64Url.encode(it)))
                assertEquals(ApiResult.Failed(ApiError.MALFORMED_RESPONSE), api.challenge())
            }
            listOf(Base64Url.encode(ByteArray(56)) + "=", Base64Url.encode(ByteArray(56)).replace('A', '+'), "AB").forEach {
                server.on("/auth/challenge", challenge(it))
                assertEquals(ApiResult.Failed(ApiError.MALFORMED_RESPONSE), api.challenge(), it)
            }
            val bytes = ByteArray(16) { it.toByte() }
            server.on("/auth/challenge", challenge(Base64Url.encode(bytes)))
            assertContentEquals(bytes, assertIs<ApiResult.Ok<ByteArray>>(api.challenge()).value)
        }

    @Test
    fun redirectsAreNotFollowed() =
        runTest {
            server.on(
                "/devices",
                { _ -> respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://elsewhere.test/devices")) },
            )
            val session = Session(DEVICE_ID, FakeAuthServer.TOKEN, 1, FakeAuthServer.ENDPOINT)
            assertEquals(ApiResult.Failed(ApiError.MALFORMED_RESPONSE), api.devices(session))
            assertEquals(1, server.requests.size)
        }

    @Test
    fun oversizedBodiesAreRefusedWithOrWithoutContentLength() =
        runTest {
            // A valid answer, padded with whitespace past the cap: only the cap can refuse it.
            val big = "{\"challenge\":\"${Base64Url.encode(ByteArray(56))}\"" + " ".repeat(GifItUpApi.MAX_BODY_BYTES) + "}"
            server.on("/auth/challenge", json(big))
            assertEquals(ApiResult.Failed(ApiError.MALFORMED_RESPONSE), api.challenge())
            server.on("/auth/challenge", { _ -> respond(ByteReadChannel(big), HttpStatusCode.OK) })
            assertEquals(ApiResult.Failed(ApiError.MALFORMED_RESPONSE), api.challenge())
        }

    @Test
    fun theTokenStaysOutOfToString() {
        val session = Session(DEVICE_ID, FakeAuthServer.TOKEN, 1, FakeAuthServer.ENDPOINT)
        assertFalse(FakeAuthServer.TOKEN in session.toString())
        assertTrue(DEVICE_ID in session.toString())
    }

    @Test
    fun registerAndLoginSendTheProtocolFieldsAsBase64Url() =
        runTest {
            val key = ByteArray(32) { 1 }
            val challenge = ByteArray(56) { 2 }
            val signature = ByteArray(64) { 3 }
            val registered = assertIs<ApiResult.Ok<Session>>(api.register("ann", "AAAAAAAAAAAAAAAAAAAAAA", key, challenge, signature))
            assertEquals(DEVICE_ID, registered.value.deviceId)
            assertIs<ApiResult.Ok<Session>>(api.login(DEVICE_ID, challenge, signature))

            val register = ProtocolJson.decodeFromString(RegisterRequest.serializer(), server.bodiesFor("/accounts").single())
            assertEquals(
                RegisterRequest(
                    "ann",
                    "AAAAAAAAAAAAAAAAAAAAAA",
                    Base64Url.encode(key),
                    Base64Url.encode(challenge),
                    Base64Url.encode(signature),
                ),
                register,
            )
            val login = ProtocolJson.decodeFromString(LoginRequest.serializer(), server.bodiesFor("/auth/login").single())
            assertEquals(LoginRequest(DEVICE_ID, Base64Url.encode(challenge), Base64Url.encode(signature)), login)
            assertContentEquals(signature, Base64Url.decode(login.signature, 64..64))
        }

    @Test
    fun devicesAreMappedAndCheckedAndLogoutSucceeds() =
        runTest {
            val session = Session(DEVICE_ID, FakeAuthServer.TOKEN, 1, FakeAuthServer.ENDPOINT)
            assertEquals(listOf(Device(DEVICE_ID, 1, 2)), assertIs<ApiResult.Ok<List<Device>>>(api.devices(session)).value)
            val bad =
                listOf(
                    """{"devices":[{"deviceId":"${DEVICE_ID.uppercase()}","createdAt":1,"lastSeenAt":2}]}""",
                    """{"devices":[{"deviceId":"$DEVICE_ID","createdAt":-1,"lastSeenAt":2}]}""",
                    """{"devices":[{"deviceId":"$DEVICE_ID","createdAt":1,"lastSeenAt":-2}]}""",
                    """{"devices":[{"deviceId":"$DEVICE_ID","createdAt":1}]}""",
                )
            bad.forEach {
                server.on("/devices", json(it))
                assertEquals(ApiResult.Failed(ApiError.MALFORMED_RESPONSE), api.devices(session), it)
            }
            assertIs<ApiResult.Ok<Unit>>(api.logout(session))
        }

    @OptIn(DevOnlyEndpoint::class)
    @Test
    fun aSessionIsNeverSentToAnotherEndpoint() =
        runTest {
            val session = assertIs<ApiResult.Ok<Session>>(api.login(DEVICE_ID, ByteArray(56), ByteArray(64))).value
            server.requests.clear()
            val others =
                listOf(
                    server.api(assertNotNull(ApiEndpoint.forOrigin("https://other.test"))),
                    server.api(assertNotNull(ApiEndpoint.loopback(FakeAuthServer.ORIGIN, 8080))),
                )
            others.forEach { other ->
                assertFailsWith<IllegalArgumentException> { other.devices(session) }
                assertFailsWith<IllegalArgumentException> { other.logout(session) }
            }
            assertEquals(emptyList(), server.requests)
            assertIs<ApiResult.Ok<List<Device>>>(server.api(ApiEndpoint.forOrigin(FakeAuthServer.ORIGIN)!!).devices(session))
        }
}
