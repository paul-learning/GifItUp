package app.gifitup.shared.data.network

import app.gifitup.shared.data.network.FakeAuthServer.Companion.DEVICE_ID
import app.gifitup.shared.data.network.FakeAuthServer.Companion.error
import app.gifitup.shared.data.network.FakeAuthServer.Companion.json
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull

/** `GET /config/gif-provider` of [GifItUpApi] (ADR-0003, #40): a key that breaks the rule is never believed. */
class GifProviderApiTest {
    private val server = FakeAuthServer()
    private val api = server.api()
    private val key = "KlipyTestKey_0123456789abcdef"

    private suspend fun session(): Session = assertIs<ApiResult.Ok<Session>>(api.login(DEVICE_ID, ByteArray(56), ByteArray(64))).value

    private suspend fun answerFor(reply: Reply): ApiResult<GifProviderKey> {
        val session = session()
        server.on("/config/gif-provider", reply)
        return api.gifProviderKey(session)
    }

    @Test
    fun aValidKeyIsReadAndOnlyTheBearerIsSent() =
        runTest {
            val session = session()
            server.on("/config/gif-provider", json("""{"apiKey":"$key"}"""))
            val got = assertIs<ApiResult.Ok<GifProviderKey>>(api.gifProviderKey(session)).value
            assertEquals(key, got.reveal())
            val request = server.requests.last()
            assertEquals(HttpMethod.Get, request.data.method)
            assertEquals("Bearer ${FakeAuthServer.TOKEN}", request.data.headers["Authorization"])
            assertEquals("", request.body)
        }

    @Test
    fun aProviderTheServerNamesIsIgnoredAndAnExtraFieldIsToo() =
        runTest {
            val answer = answerFor(json("""{"provider":"giphy","apiKey":"$key","note":"x"}"""))
            assertEquals(key, assertIs<ApiResult.Ok<GifProviderKey>>(answer).value.reveal())
        }

    @Test
    fun anyKeyOutsideTheRuleRefusesTheWholeAnswer() =
        runTest {
            listOf(
                "short",
                "a".repeat(129),
                "$key/../x",
                "$key?x=1",
                "$key#",
                "$key%2F",
                "$key.",
                "$key ",
                " $key",
                "$key\\n",
                "${key}ü",
                "",
            ).forEach { bad ->
                assertEquals(ApiResult.Failed(ApiError.MALFORMED_RESPONSE), answerFor(json("""{"apiKey":"$bad"}""")), bad)
            }
            assertEquals(ApiResult.Failed(ApiError.MALFORMED_RESPONSE), answerFor(json("""{}""")), "missing")
            assertEquals(ApiResult.Failed(ApiError.MALFORMED_RESPONSE), answerFor(json("""{"apiKey":7}""")), "not a string")
            assertEquals(ApiResult.Failed(ApiError.MALFORMED_RESPONSE), answerFor(json("nope")), "not JSON")
        }

    @Test
    fun aBodyOverTheCapIsRefused() =
        runTest {
            assertEquals(
                ApiResult.Failed(ApiError.MALFORMED_RESPONSE),
                answerFor(json("""{"apiKey":"$key"}""" + " ".repeat(2048))),
            )
        }

    @Test
    fun theServersRefusalsAreMapped() =
        runTest {
            assertEquals(
                ApiResult.Failed(ApiError.NOT_FOUND),
                answerFor(error(HttpStatusCode.NotFound, "not_found")),
                "no key: no GIF search yet",
            )
            assertEquals(ApiResult.Failed(ApiError.RATE_LIMITED), answerFor(error(HttpStatusCode.TooManyRequests, "rate_limited")))
            assertEquals(ApiResult.Failed(ApiError.UNAUTHORIZED), answerFor(error(HttpStatusCode.Unauthorized, "unauthorized")))
        }

    @Test
    fun theHolderNeverPrintsAndOnlyAValidKeyMakesOne() {
        val holder = assertIs<GifProviderKey>(GifProviderKey.of(key))
        assertEquals("GifProviderKey(redacted)", holder.toString())
        assertFalse(key in "$holder")
        assertNull(GifProviderKey.of("$key/"))
    }
}
