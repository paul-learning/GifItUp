package app.gifitup.server

import app.gifitup.protocol.PROTOCOL_VERSION
import app.gifitup.protocol.PROTOCOL_VERSION_HEADER
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.runBlocking
import java.net.Socket
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Limits enforced by the real Netty engine with the production settings (#12 SEC-8). Raw sockets, because peers on the
 * shared `proxy` network talk to the server directly, without Caddy or a well-behaved client in between.
 */
class NettyLimitsTest {
    // A short deadline keeps the connection tests fast; the slow route takes longer than it.
    private val config = testConfig(HttpLimits(requestDeadlineSeconds = 1))
    private val server =
        embeddedServer(
            Netty,
            configure = {
                applyTo(this, config)
                // Bind to an ephemeral port instead of guessing a free one (no race with other processes).
                connectors.clear()
                connector { port = 0 }
            },
            module = { testModule(config) },
        )
    private var port = 0

    @BeforeTest
    fun start() {
        server.start(wait = false)
        port =
            runBlocking {
                server.engine
                    .resolvedConnectors()
                    .single()
                    .port
            }
    }

    @AfterTest
    fun stop() {
        server.stop(gracePeriodMillis = 0, timeoutMillis = 1_000)
    }

    @Test
    fun healthWorksOverTheRealEngine() {
        assertEquals(200, statusOf("GET /health HTTP/1.1\r\nHost: t\r\nConnection: close\r\n\r\n"))
    }

    // Netty's decoder answers 400 (not 431/414) and closes the connection; the request never reaches the application.
    @Test
    fun oversizedHeadersAreRejected() {
        val big = "a".repeat(config.limits.maxHeaderSize + 1)

        assertEquals(400, statusOf("GET /health HTTP/1.1\r\nHost: t\r\nX-Big: $big\r\nConnection: close\r\n\r\n"))
    }

    @Test
    fun oversizedRequestLineIsRejected() {
        val path = "/" + "a".repeat(config.limits.maxInitialLineLength + 1)

        assertEquals(400, statusOf("GET $path HTTP/1.1\r\nHost: t\r\nConnection: close\r\n\r\n"))
    }

    @Test
    fun chunkedBodyOverTheLimitIsRejectedWithoutContentLength() {
        val chunk = "a".repeat(4_096)
        val chunks = (config.limits.defaultBodyLimitBytes / chunk.length + 2).toInt()
        val body = buildString { repeat(chunks) { append("1000\r\n").append(chunk).append("\r\n") } }.plus("0\r\n\r\n")

        val status =
            statusOf(
                "POST $ECHO_PATH HTTP/1.1\r\nHost: t\r\n$PROTOCOL_VERSION_HEADER: $PROTOCOL_VERSION\r\n" +
                    "Content-Type: application/json\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n$body",
            )

        assertEquals(413, status)
    }

    @Test
    fun declaredContentLengthOverTheLimitIsRejected() {
        val status =
            statusOf(
                "POST $ECHO_PATH HTTP/1.1\r\nHost: t\r\n$PROTOCOL_VERSION_HEADER: $PROTOCOL_VERSION\r\n" +
                    "Content-Type: application/json\r\nContent-Length: ${config.limits.defaultBodyLimitBytes + 1}\r\n" +
                    "Connection: close\r\n\r\n{",
            )

        assertEquals(413, status)
    }

    @Test
    fun silentConnectionIsClosed() = assertClosedWithinDeadline { }

    @Test
    fun partialRequestLineIsClosed() = assertClosedWithinDeadline { it.write("GET /hea".toByteArray()) }

    @Test
    fun tricklingHeadersAreClosedDespiteSteadyTraffic() =
        assertClosedWithinDeadline { out ->
            out.write("GET /health HTTP/1.1\r\nHost: t\r\n".toByteArray())
            // One header byte every 200 ms: never idle, but never a complete request either.
            repeat(TRICKLE_STEPS) {
                runCatching {
                    out.write('x'.code)
                    out.flush()
                }
                Thread.sleep(TRICKLE_MILLIS)
            }
        }

    @Test
    fun idleKeepAliveIsClosedAfterTheResponse() {
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = READ_TIMEOUT_MILLIS
            socket.getOutputStream().write("GET /health HTTP/1.1\r\nHost: t\r\n\r\n".toByteArray())
            val input = socket.getInputStream()
            val started = System.nanoTime()

            // Reads the whole response, then end of stream once the deadline closes the idle connection.
            while (input.read() != -1) Unit

            assertClosedInTime(started)
        }
    }

    @Test
    fun slowHandlerIsNotCutOffByTheDeadline() {
        assertTrue(SLOW_MILLIS > config.limits.requestDeadlineSeconds * MILLIS_PER_SECOND)

        val request =
            "GET $SLOW_PATH HTTP/1.1\r\nHost: t\r\n$PROTOCOL_VERSION_HEADER: $PROTOCOL_VERSION\r\nConnection: close\r\n\r\n"

        assertEquals(204, statusOf(request))
    }

    // #68 SEC-19: the server answers before the body arrives; once the body completes, the connection still has a deadline.
    @Test
    fun earlyVersionRejectBeforeTheBodyKeepsTheDeadline() =
        assertDeadlineAfterEarlyResponse("POST /anything HTTP/1.1\r\nHost: t\r\nContent-Length: 1\r\n\r\n", 400)

    @Test
    fun healthWithABodyKeepsTheDeadline() =
        assertDeadlineAfterEarlyResponse("GET /health HTTP/1.1\r\nHost: t\r\nContent-Length: 1\r\n\r\n", 200)

    @Test
    fun unauthorizedBeforeTheBodyKeepsTheDeadline() =
        assertDeadlineAfterEarlyResponse(
            "POST /nope HTTP/1.1\r\nHost: t\r\n$PROTOCOL_VERSION_HEADER: $PROTOCOL_VERSION\r\nContent-Length: 1\r\n\r\n",
            401,
        )

    // #68 SEC-20: a 100 Continue is not the answer to the request, so a slow handler after it is not cut off.
    @Test
    fun expectContinueDoesNotCountAsTheResponse() {
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = READ_TIMEOUT_MILLIS
            val out = socket.getOutputStream()
            val input = socket.getInputStream()
            out.write(
                (
                    "POST $SLOW_PATH HTTP/1.1\r\nHost: t\r\n$PROTOCOL_VERSION_HEADER: $PROTOCOL_VERSION\r\n" +
                        "Content-Type: application/octet-stream\r\nContent-Length: 2\r\nExpect: 100-continue\r\n" +
                        "Connection: close\r\n\r\n"
                ).toByteArray(),
            )

            assertEquals(100, readResponse(input))
            out.write("ab".toByteArray())
            assertEquals(204, readResponse(input))
        }
    }

    /** Sends [head] (announcing a 1-byte body), reads the early response, sends the byte, and expects a close in time. */
    private fun assertDeadlineAfterEarlyResponse(
        head: String,
        expectedStatus: Int,
    ) {
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = READ_TIMEOUT_MILLIS
            socket.getOutputStream().write(head.toByteArray())
            val input = socket.getInputStream()
            assertEquals(expectedStatus, readResponse(input))

            runCatching { socket.getOutputStream().write('x'.code) }
            val started = System.nanoTime()
            val read = runCatching { input.read() }

            assertTrue(read.getOrNull() == -1 || read.exceptionOrNull() is java.net.SocketException, "still open: $read")
            assertClosedInTime(started)
        }
    }

    /** Reads one response (head and a Content-Length body) byte by byte, so later bytes stay unread. Returns its status. */
    private fun readResponse(input: java.io.InputStream): Int {
        val head = StringBuilder()
        while (!head.endsWith("\r\n\r\n")) {
            val b = input.read()
            check(b != -1) { "connection closed in the response head: $head" }
            head.append(b.toChar())
        }
        val lines = head.lines()
        val length =
            lines
                .firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
                ?.substringAfter(':')
                ?.trim()
                ?.toInt() ?: 0
        repeat(length) { check(input.read() != -1) { "connection closed in the response body" } }
        return lines.first().split(' ')[1].toInt()
    }

    /**
     * Opens a connection, runs [send] on a background thread (it may keep writing), and asserts that the server closes
     * the connection within the deadline plus some slack, measured on the reading side.
     */
    private fun assertClosedWithinDeadline(send: (java.io.OutputStream) -> Unit) {
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = READ_TIMEOUT_MILLIS
            val started = System.nanoTime()
            val sender = thread(isDaemon = true) { runCatching { send(socket.getOutputStream()) } }

            val read = runCatching { socket.getInputStream().read() }
            assertClosedInTime(started)
            sender.join(READ_TIMEOUT_MILLIS.toLong())

            // End of stream, or a reset when the server closed while we were still writing.
            assertTrue(read.getOrNull() == -1 || read.exceptionOrNull() is java.net.SocketException, "still open: $read")
        }
    }

    private fun assertClosedInTime(startedNanos: Long) {
        val elapsedMillis = (System.nanoTime() - startedNanos) / NANOS_PER_MILLI
        assertTrue(
            elapsedMillis < config.limits.requestDeadlineSeconds * MILLIS_PER_SECOND + SLACK_MILLIS,
            "closed after $elapsedMillis ms",
        )
    }

    private fun statusOf(request: String): Int =
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = READ_TIMEOUT_MILLIS
            // The server may answer and close before the whole request is written.
            runCatching { socket.getOutputStream().apply { write(request.toByteArray()) }.flush() }
            val statusLine = socket.getInputStream().bufferedReader().readLine()
            assertTrue(statusLine != null && statusLine.matches(STATUS_LINE), "no status line: $statusLine")
            statusLine.split(' ')[1].toInt()
        }

    private companion object {
        const val READ_TIMEOUT_MILLIS = 5_000
        const val SLACK_MILLIS = 1_500L
        const val MILLIS_PER_SECOND = 1_000L
        const val NANOS_PER_MILLI = 1_000_000L
        const val TRICKLE_STEPS = 15
        const val TRICKLE_MILLIS = 200L
        val STATUS_LINE = Regex("""^HTTP/1\.[01] [0-9]{3} .*""")
    }
}
