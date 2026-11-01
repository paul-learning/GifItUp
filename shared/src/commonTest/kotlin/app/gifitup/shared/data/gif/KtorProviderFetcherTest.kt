package app.gifitup.shared.data.gif

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KtorProviderFetcherTest {
    private val key = "KlipyTestKey_0123456789abcdef"
    private val url = "https://api.klipy.com/api/v1/$key/gifs/trending"

    private fun fetcher(
        handler:
            suspend io.ktor.client.engine.mock.MockRequestHandleScope.(
                io.ktor.client.request.HttpRequestData,
            ) -> io.ktor.client.request.HttpResponseData,
    ) = KtorProviderFetcher(MockEngine(handler))

    @Test
    fun aCompleteOkAnswerGivesTheBodyAndSendsOnlyAnAcceptHeader() =
        runTest {
            var seen: io.ktor.client.request.HttpRequestData? = null
            val answer =
                fetcher { request ->
                    seen = request
                    respond("{}", HttpStatusCode.OK)
                }.get(url, 1000)
            assertEquals("{}", assertIs<ProviderAnswer.Reply>(answer).body.decodeToString())
            val headers = seen!!.headers
            assertEquals("application/json", headers[HttpHeaders.Accept])
            listOf(HttpHeaders.Cookie, HttpHeaders.Referrer, HttpHeaders.Authorization).forEach { assertNull(headers[it], it) }
        }

    @Test
    fun aRedirectIsAReplyWithThatStatusAndIsNeverFollowed() =
        runTest {
            var requests = 0
            val answer =
                fetcher {
                    requests++
                    respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://evil.example/$key"))
                }.get(url, 1000)
            assertEquals(302, assertIs<ProviderAnswer.Reply>(answer).status)
            assertEquals(1, requests)
        }

    @Test
    fun aRefusalCarriesTheStatusAndTheRetryAfterSecondsButNoBody() =
        runTest {
            val answer = fetcher { respond("nope", HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.RetryAfter, "30")) }.get(url, 1000)
            val reply = assertIs<ProviderAnswer.Reply>(answer)
            assertEquals(429, reply.status)
            assertEquals(30, reply.retryAfterSeconds)
            assertEquals(0, reply.body.size)
        }

    @Test
    fun retryAfterIsWholeSecondsOnly() {
        assertEquals(30, retryAfterSeconds("30"))
        assertEquals(3600, retryAfterSeconds("3600"))
        assertNull(retryAfterSeconds("3601"))
        assertNull(retryAfterSeconds("-1"))
        assertNull(retryAfterSeconds("1.5"))
        assertNull(retryAfterSeconds("Wed, 21 Oct 2026 07:28:00 GMT"))
        assertNull(retryAfterSeconds(""))
        assertNull(retryAfterSeconds(null))
    }

    @Test
    fun aDeclaredOrActualLengthOverTheCapIsTooLarge() =
        runTest {
            val declared =
                fetcher {
                    respond(
                        "x".repeat(10),
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentLength, "5000"),
                    )
                }.get(url, 100)
            assertEquals(ProviderAnswer.TooLarge, declared)
            val actual = fetcher { respond("x".repeat(101), HttpStatusCode.OK) }.get(url, 100)
            assertEquals(ProviderAnswer.TooLarge, actual)
            val exact = fetcher { respond("x".repeat(100), HttpStatusCode.OK) }.get(url, 100)
            assertEquals(100, assertIs<ProviderAnswer.Reply>(exact).body.size)
        }

    @Test
    fun anExceptionWhoseMessageQuotesTheUrlNeverComesOut() =
        runTest {
            val answer = fetcher { error("timeout for $url") }.get(url, 100)
            assertEquals(ProviderAnswer.Failed, answer)
            assertFalse(key in answer.toString())
            val platform = fetcher { throw AssertionError("fetch failed $url") }.get(url, 100)
            assertTrue(platform === ProviderAnswer.Failed)
        }

    @Test
    fun aSearchThatIsReplacedDoesNotCancelTheRequestOnTheWire() =
        runTest {
            val started = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Unit>()
            var finished = false
            val slow =
                KtorProviderFetcher(
                    MockEngine {
                        started.complete(Unit)
                        gate.await()
                        finished = true
                        respond("{}", HttpStatusCode.OK)
                    },
                )
            val call = launch(Dispatchers.Default) { slow.get(url, 1000) }
            started.await()
            call.cancel()
            gate.complete(Unit)
            call.join()
            assertTrue(finished, "see KtorMediaFetcher: cancelling an Android response mid-read can crash the app")
            assertTrue(call.isCancelled)
        }
}
