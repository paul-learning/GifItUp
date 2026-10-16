package app.gifitup.server

import app.gifitup.protocol.PROTOCOL_VERSION
import app.gifitup.protocol.PROTOCOL_VERSION_HEADER
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import java.net.ServerSocket
import java.net.Socket
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
    private val config = ServerConfig(port = freePort())
    private val server =
        embeddedServer(Netty, configure = { applyTo(this, config) }, module = { testModule(config) })

    @BeforeTest
    fun start() {
        server.start(wait = false)
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

    private fun statusOf(request: String): Int =
        Socket("127.0.0.1", config.port).use { socket ->
            socket.soTimeout = 5_000
            // The server may answer and close before the whole request is written.
            runCatching { socket.getOutputStream().apply { write(request.toByteArray()) }.flush() }
            val statusLine = socket.getInputStream().bufferedReader().readLine()
            assertTrue(statusLine != null && statusLine.matches(STATUS_LINE), "no status line: $statusLine")
            statusLine.split(' ')[1].toInt()
        }

    private companion object {
        val STATUS_LINE = Regex("""^HTTP/1\.[01] [0-9]{3} .*""")

        fun freePort(): Int = ServerSocket(0).use { it.localPort }
    }
}
