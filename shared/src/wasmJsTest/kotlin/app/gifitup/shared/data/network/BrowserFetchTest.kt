package app.gifitup.shared.data.network

import app.gifitup.shared.data.gif.BrowserApiFetch
import app.gifitup.shared.data.gif.ProviderAnswer
import app.gifitup.shared.domain.media.FetchResult
import app.gifitup.shared.domain.media.MediaFormat
import app.gifitup.shared.domain.media.MediaInfo
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.await
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.js.JsAny
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** `BrowserFetch` against a real HTTP server on loopback, run by Node's `fetch()` (the same API a browser has). */
@Suppress("TooManyFunctions", "MagicNumber") // One test per behaviour of one call; sizes are test data.
class BrowserFetchTest {
    private suspend fun <T> withServer(block: suspend (base: String, server: JsAny) -> T): T {
        val server = startServer().await<JsAny>()
        try {
            return block("http://127.0.0.1:${serverPort(server)}", server)
        } finally {
            stopServer(server)
        }
    }

    @Test
    fun aCompleteOkAnswerGivesTheBytes() =
        runTest {
            withServer { base, _ ->
                val result = BrowserFetch.fetchCapped("$base/gif")
                assertEquals("GIF89a", assertIs<FetchResult.Bytes>(result).data.decodeToString())
            }
        }

    @Test
    fun theRequestCarriesNoCookiesRefererOrAuthorization() =
        runTest {
            withServer { base, _ ->
                val result = BrowserFetch.fetchCapped("$base/headers")
                val headers = assertIs<FetchResult.Bytes>(result).data.decodeToString().lowercase()
                assertTrue("\"accept\":\"image/webp,image/gif\"" in headers, headers)
                // Node sends no Origin; a browser adds it to a cross-origin request (documented in the threat model).
                for (name in listOf("cookie", "referer", "authorization")) {
                    assertFalse("\"$name\"" in headers, "$name was sent: $headers")
                }
            }
        }

    @Test
    fun aRedirectIsAFailureAndTheTargetIsNeverRequested() =
        runTest {
            withServer { base, server ->
                assertEquals(FetchResult.Failed, BrowserFetch.fetchCapped("$base/redirect"))
                assertEquals(1, requestCount(server), "only the first request reached the server")
            }
        }

    @Test
    fun errorStatusesAreFailures() =
        runTest {
            withServer { base, _ ->
                assertEquals(FetchResult.Failed, BrowserFetch.fetchCapped("$base/missing"))
                assertEquals(FetchResult.Failed, BrowserFetch.fetchCapped("$base/partial"))
            }
        }

    @Test
    fun aDeclaredLengthOverTheCapIsRefused() =
        runTest {
            withServer { base, _ ->
                assertEquals(FetchResult.TooLarge, BrowserFetch.fetchCapped("$base/declared", maxBytes = 1024))
            }
        }

    @Test
    fun anEndlessBodyStopsAtTheCapWithoutBeingBuffered() =
        runTest {
            withServer { base, server ->
                val result = BrowserFetch.fetchCapped("$base/big", maxBytes = 256 * 1024)
                assertEquals(FetchResult.TooLarge, result)
                assertTrue(bytesSent(server) < 16 * 1024 * 1024, "the server was held back: ${bytesSent(server)} bytes were taken")
            }
        }

    @Test
    fun aBodyAtExactlyTheCapIsAccepted() =
        runTest {
            withServer { base, _ ->
                val result = BrowserFetch.fetchCapped("$base/exact", maxBytes = 1000)
                assertEquals(1000, assertIs<FetchResult.Bytes>(result).data.size)
                assertEquals(FetchResult.TooLarge, BrowserFetch.fetchCapped("$base/exact", maxBytes = 999))
            }
        }

    @Test
    fun aServerThatNeverAnswersIsAbortedAtTheTimeout() =
        runTest {
            withServer { base, _ ->
                assertEquals(FetchResult.Failed, BrowserFetch.fetchCapped("$base/hang", timeoutMs = 300))
            }
        }

    @Test
    fun anUnreachableHostIsAFailure() =
        runTest {
            assertEquals(FetchResult.Failed, BrowserFetch.fetchCapped("http://127.0.0.1:1/x", timeoutMs = 3000))
        }

    @Test
    fun bytesCrossTheJavaScriptBoundaryInOneCopyFast() {
        val data = ByteArray(8 * 1024 * 1024) { (it * 31).toByte() }
        val started =
            kotlin.time.TimeSource.Monotonic
                .markNow()
        val back = data.toJsBytes().toByteArray()
        assertTrue(back.contentEquals(data))
        assertTrue(started.elapsedNow() < kotlin.time.Duration.parse("10s"), "took ${started.elapsedNow()}")
    }

    /** The test scheduler skips `delay`; the server and the network run in real time. */
    private suspend fun realDelay(millis: Long) = withContext(Dispatchers.Default) { delay(millis) }

    @Test
    fun aCancelledCallStopsTheDownload() =
        runTest {
            withServer { base, server ->
                val job = launch(start = CoroutineStart.UNDISPATCHED) { BrowserFetch.fetchCapped("$base/big", maxBytes = 1_000_000_000) }
                while (bytesSent(server) == 0) realDelay(10)
                job.cancelAndJoin()
                realDelay(300)
                val afterCancel = bytesSent(server)
                realDelay(300)
                assertEquals(afterCancel, bytesSent(server), "the server stopped being read from")
            }
        }

    @Test
    fun theApiFetchGivesTheStatusAndBodyOfAnAnswerAndFailsOnARedirect() =
        runTest {
            withServer { base, server ->
                val ok = BrowserApiFetch.fetch("$base/gif", 1024, 5000)
                assertEquals("GIF89a", assertIs<ProviderAnswer.Reply>(ok).body.decodeToString())
                val missing = assertIs<ProviderAnswer.Reply>(BrowserApiFetch.fetch("$base/missing", 1024, 5000))
                assertEquals(404, missing.status)
                assertEquals(0, missing.body.size)
                assertEquals(ProviderAnswer.Failed, BrowserApiFetch.fetch("$base/redirect", 1024, 5000))
                assertEquals(3, requestCount(server), "the redirect target was never requested")
            }
        }

    @Test
    fun theApiFetchSendsNoCookiesRefererOrAuthorizationAndStopsAtTheCap() =
        runTest {
            withServer { base, _ ->
                val headers =
                    assertIs<ProviderAnswer.Reply>(
                        BrowserApiFetch.fetch("$base/headers", 4096, 5000),
                    ).body.decodeToString().lowercase()
                assertTrue("\"accept\":\"application/json\"" in headers, headers)
                for (name in listOf("cookie", "referer", "authorization")) {
                    assertFalse("\"$name\"" in headers, headers)
                }
                assertEquals(ProviderAnswer.TooLarge, BrowserApiFetch.fetch("$base/declared", 1024, 5000))
                assertEquals(ProviderAnswer.TooLarge, BrowserApiFetch.fetch("$base/big", 256 * 1024, 5000))
            }
        }

    @Test
    fun blobUrlsCarryTheScannedType() {
        val info = MediaInfo(MediaFormat.GIF, 1, 1, 1, 6)
        val url = BrowserBlobUrl.create("GIF89a".encodeToByteArray(), info)
        assertTrue(url.startsWith("blob:"), url)
        BrowserBlobUrl.revoke(url)
    }
}

// A throwaway HTTP server for the tests: node:http on an ephemeral loopback port. The handle counts requests (not
// favicon-style extras) and the bytes written to the endless route.
@JsFun(
    """
() => import('node:http').then((http) => {
  const state = { requests: 0, sent: 0, server: null, port: 0 };
  state.server = http.createServer((req, res) => {
    state.requests += 1;
    if (req.url === '/gif') {
      res.writeHead(200, { 'content-type': 'image/gif' });
      res.end(Buffer.from('GIF89a'));
    } else if (req.url === '/headers') {
      res.writeHead(200, { 'content-type': 'application/json' });
      res.end(JSON.stringify(req.headers));
    } else if (req.url === '/redirect') {
      res.writeHead(302, { location: '/gif' });
      res.end();
    } else if (req.url === '/partial') {
      res.writeHead(206, { 'content-type': 'image/gif' });
      res.end(Buffer.from('GIF89a'));
    } else if (req.url === '/declared') {
      res.writeHead(200, { 'content-length': '9000000' });
      res.write(Buffer.alloc(10));
    } else if (req.url === '/exact') {
      res.writeHead(200, { 'content-type': 'image/gif' });
      res.end(Buffer.alloc(1000));
    } else if (req.url === '/big') {
      res.writeHead(200);
      const chunk = Buffer.alloc(64 * 1024);
      const pump = () => {
        while (!res.destroyed) {
          state.sent += chunk.length;
          if (!res.write(chunk)) { res.once('drain', pump); return; }
        }
      };
      pump();
    } else if (req.url === '/hang') {
      res.writeHead(200);
    } else {
      res.writeHead(404);
      res.end();
    }
  });
  return new Promise((resolve) => {
    state.server.listen(0, '127.0.0.1', () => {
      state.port = state.server.address().port;
      resolve(state);
    });
  });
})
""",
)
private external fun startServer(): Promise<JsAny>

@JsFun("(state) => state.port")
private external fun serverPort(state: JsAny): Int

@JsFun("(state) => state.requests")
private external fun requestCount(state: JsAny): Int

@JsFun("(state) => state.sent")
private external fun bytesSent(state: JsAny): Int

@JsFun("(state) => { state.server.closeAllConnections(); state.server.close(); }")
private external fun stopServer(state: JsAny)
