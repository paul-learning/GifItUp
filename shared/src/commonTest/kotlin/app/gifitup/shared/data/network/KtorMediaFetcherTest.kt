package app.gifitup.shared.data.network

import app.gifitup.shared.data.content.GifReference
import app.gifitup.shared.domain.media.FetchResult
import app.gifitup.shared.domain.media.LoadGifMedia
import app.gifitup.shared.domain.media.MediaLoad
import app.gifitup.shared.domain.media.MediaScanner
import app.gifitup.shared.domain.media.UnavailableReason
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

class KtorMediaFetcherTest {
    private val url = "https://static.klipy.com/ii/0123456789abcdef0123456789abcdef/ab/cd/funny_cat-1.webp"
    private val reference = assertNotNull(GifReference.of("klipy", "funny-cat", url, 320, 240))
    private val requests = mutableListOf<HttpRequestData>()

    /** A 1x1 GIF: header, screen descriptor, one image, trailer. */
    private val tinyGif =
        "GIF89a".encodeToByteArray() + byteArrayOf(1, 0, 1, 0, 0, 0, 0) +
            byteArrayOf(0x2C, 0, 0, 0, 0, 1, 0, 1, 0, 0) + byteArrayOf(2, 1, 0x44, 0) + byteArrayOf(0x3B)

    private fun fetcher(
        handler: suspend io.ktor.client.engine.mock.MockRequestHandleScope.(HttpRequestData) -> io.ktor.client.request.HttpResponseData,
    ) = KtorMediaFetcher(
        MockEngine { request ->
            requests += request
            handler(request)
        },
    )

    @Test
    fun aCompleteOkAnswerGivesTheBytes() =
        runTest {
            val result = fetcher { respond(tinyGif, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "image/gif")) }.fetch(reference)
            assertTrue(assertIs<FetchResult.Bytes>(result).data.contentEquals(tinyGif))
        }

    @Test
    fun theRequestIsASimpleGetWithoutCookiesOrReferer() =
        runTest {
            fetcher { respond(tinyGif, HttpStatusCode.OK) }.fetch(reference)
            val request = requests.single()
            assertEquals(url, request.url.toString())
            assertEquals("GET", request.method.value)
            val names = request.headers.names().map { it.lowercase() }
            assertTrue("cookie" !in names && "referer" !in names && "authorization" !in names, names.toString())
        }

    @Test
    fun aRedirectIsAFailureAndIsNotFollowed() =
        runTest {
            val result =
                fetcher {
                    respond(
                        "",
                        HttpStatusCode.Found,
                        headersOf(HttpHeaders.Location, "https://evil.example/x.gif"),
                    )
                }.fetch(reference)
            assertEquals(FetchResult.Failed, result)
            assertEquals(1, requests.size, "the Location was not requested")
        }

    @Test
    fun errorStatusesAreFailures() =
        runTest {
            for (status in listOf(
                HttpStatusCode.NotFound,
                HttpStatusCode.Forbidden,
                HttpStatusCode.TooManyRequests,
                HttpStatusCode.InternalServerError,
            )) {
                assertEquals(FetchResult.Failed, fetcher { respondError(status) }.fetch(reference), status.toString())
            }
            assertEquals(FetchResult.Failed, fetcher { respond(tinyGif, HttpStatusCode.PartialContent) }.fetch(reference))
            assertEquals(FetchResult.Failed, fetcher { respond(ByteArray(0), HttpStatusCode.NoContent) }.fetch(reference))
        }

    @Test
    fun aDeclaredLengthOverTheCapIsRefused() =
        runTest {
            val body = ByteArray(MediaScanner.MAX_BYTES + 1)
            val result =
                fetcher {
                    respond(
                        body,
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentLength, body.size.toString()),
                    )
                }.fetch(reference)
            assertEquals(FetchResult.TooLarge, result)
        }

    @Test
    fun aBodyOverTheCapIsRefusedEvenWithoutADeclaredLength() =
        runTest {
            val result = fetcher { respond(ByteArray(MediaScanner.MAX_BYTES + 1), HttpStatusCode.OK) }.fetch(reference)
            assertEquals(FetchResult.TooLarge, result)
            val exact = fetcher { respond(ByteArray(MediaScanner.MAX_BYTES), HttpStatusCode.OK) }.fetch(reference)
            assertEquals(MediaScanner.MAX_BYTES, assertIs<FetchResult.Bytes>(exact).data.size)
        }

    /** A body that keeps coming (up to 64 MiB), held back by the channel so it can't be far ahead of the reader. */
    private fun CoroutineScope.endlessBody(): Pair<ByteChannel, Job> {
        val channel = ByteChannel()
        val chunk = ByteArray(64 * 1024)
        val producer =
            launch(Dispatchers.Default) {
                try {
                    repeat(1024) { channel.writeFully(chunk) }
                } catch (e: CancellationException) {
                    throw e
                } catch (ignored: Throwable) {
                    // The reader went away.
                }
            }
        return channel to producer
    }

    @Test
    fun theBodyIsReadWhileItArrivesAndTheRestIsDroppedAtOnce() =
        runTest {
            val (channel, producer) = endlessBody()
            val started = TimeSource.Monotonic.markNow()
            val result = fetcher { respond(channel, HttpStatusCode.OK) }.fetch(reference)
            val took = started.elapsedNow()
            producer.cancelAndJoin()
            assertEquals(FetchResult.TooLarge, result)
            // Not buffered (the heap would be gone) and not waited for (the request timeout is 30 s).
            assertTrue(took < 10.seconds, "took $took")
        }

    @Test
    fun theRestOfAnUnwantedBodyIsDroppedAtOnceToo() =
        runTest {
            val (channel, producer) = endlessBody()
            val started = TimeSource.Monotonic.markNow()
            val result = fetcher { respond(channel, HttpStatusCode.NotFound) }.fetch(reference)
            val took = started.elapsedNow()
            producer.cancelAndJoin()
            assertEquals(FetchResult.Failed, result)
            assertTrue(took < 10.seconds, "took $took")
        }

    @Test
    @Suppress("TooGenericExceptionThrown", "SwallowedException") // The test throws what a platform engine throws.
    fun anyThrowableIsAFailureButCancellationIsNot() =
        runTest {
            assertEquals(FetchResult.Failed, fetcher { throw IllegalStateException("SECRET-MARKER") }.fetch(reference))
            assertEquals(FetchResult.Failed, fetcher { throw Error("engine error") }.fetch(reference))
            var cancelled = false
            try {
                fetcher { throw kotlinx.coroutines.CancellationException("stop") }.fetch(reference)
            } catch (e: kotlinx.coroutines.CancellationException) {
                cancelled = true
            }
            assertTrue(cancelled)
        }

    @Test
    fun theLoaderScansWhatWasFetchedAndShowsPlaceholdersOtherwise() =
        runTest {
            val ready = LoadGifMedia { FetchResult.Bytes(tinyGif) }(reference)
            val info = assertIs<MediaLoad.Ready>(ready).info
            assertEquals(listOf(1, 1, 1), listOf(info.width, info.height, info.frames))
            val padded = LoadGifMedia { FetchResult.Bytes(tinyGif + byteArrayOf(9, 9, 9)) }(reference)
            assertTrue(assertIs<MediaLoad.Ready>(padded).data.contentEquals(tinyGif), "bytes after the trailer are not handed on")
            val html = LoadGifMedia { FetchResult.Bytes("<html>".encodeToByteArray()) }(reference)
            assertEquals(UnavailableReason.REJECTED, assertIs<MediaLoad.Unavailable>(html).reason)
            assertEquals(
                UnavailableReason.FETCH_FAILED,
                assertIs<MediaLoad.Unavailable>(LoadGifMedia { FetchResult.Failed }(reference)).reason,
            )
            assertEquals(
                UnavailableReason.TOO_LARGE,
                assertIs<MediaLoad.Unavailable>(LoadGifMedia { FetchResult.TooLarge }(reference)).reason,
            )
        }

    @Test
    fun aCallerThatLeavesDoesNotCancelTheRequestOnTheWireAndGetsItsCancellationAfterwards() =
        runTest {
            val started = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Unit>()
            var finished = false
            val slow =
                KtorMediaFetcher(
                    MockEngine {
                        started.complete(Unit)
                        gate.await()
                        finished = true
                        respond(tinyGif, HttpStatusCode.OK)
                    },
                )
            val call = launch(Dispatchers.Default) { slow.fetch(reference) }
            started.await()
            call.cancel()
            gate.complete(Unit)
            call.join()
            assertTrue(finished, "the request ran to its end: Android's HttpURLConnection can't be cancelled mid-read")
            assertTrue(call.isCancelled, "and the caller is cancelled")
        }
}
