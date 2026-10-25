package app.gifitup.server.delivery

import app.gifitup.protocol.PROTOCOL_VERSION
import app.gifitup.protocol.PROTOCOL_VERSION_HEADER
import app.gifitup.protocol.ProtocolJson
import app.gifitup.protocol.SocketHello
import app.gifitup.server.AlwaysReady
import app.gifitup.server.applyTo
import app.gifitup.server.auth.AuthService
import app.gifitup.server.auth.Challenges
import app.gifitup.server.auth.DbInviteRedeemer
import app.gifitup.server.auth.TestClock
import app.gifitup.server.db.APP_ROLE
import app.gifitup.server.db.Database
import app.gifitup.server.db.DatabaseConfig
import app.gifitup.server.db.TestPostgres
import app.gifitup.server.db.TransactionRunner
import app.gifitup.server.invites.InviteService
import app.gifitup.server.module
import app.gifitup.server.noDatabaseKeyPackages
import app.gifitup.server.noDatabaseMembers
import app.gifitup.server.testConfig
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.time.Clock
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/** A JDK WebSocket client that records what it receives. */
internal class SocketClient(
    port: Int,
    path: String = "/ws",
) {
    val messages = LinkedBlockingQueue<String>()
    val closed = CompletableFuture<Int>()
    private val socket: CompletableFuture<WebSocket> =
        HttpClient.newHttpClient().newWebSocketBuilder().buildAsync(
            URI("ws://localhost:$port$path"),
            object : WebSocket.Listener {
                override fun onOpen(webSocket: WebSocket) = webSocket.request(Long.MAX_VALUE)

                override fun onText(
                    webSocket: WebSocket,
                    data: CharSequence,
                    last: Boolean,
                ): CompletableFuture<*>? {
                    messages.add(data.toString())
                    return null
                }

                override fun onClose(
                    webSocket: WebSocket,
                    statusCode: Int,
                    reason: String,
                ): CompletableFuture<*>? {
                    closed.complete(statusCode)
                    return null
                }

                override fun onError(
                    webSocket: WebSocket,
                    error: Throwable,
                ) {
                    closed.complete(ABNORMAL)
                }
            },
        )

    /** The handshake failed (the server answered something other than an upgrade). */
    fun failedToConnect(): Boolean = runCatching { socket.get(WAIT_SECONDS, TimeUnit.SECONDS) }.isFailure

    fun send(text: String) {
        socket.get(WAIT_SECONDS, TimeUnit.SECONDS).sendText(text, true).get(WAIT_SECONDS, TimeUnit.SECONDS)
    }

    fun hello(
        token: String,
        version: Int = PROTOCOL_VERSION,
    ) = send(ProtocolJson.encodeToString(SocketHello(version, token)))

    fun next(): String? = messages.poll(WAIT_SECONDS, TimeUnit.SECONDS)

    fun closeCode(): Int? = runCatching { closed.get(WAIT_SECONDS, TimeUnit.SECONDS) }.getOrNull()

    fun close() {
        runCatching { socket.get(WAIT_SECONDS, TimeUnit.SECONDS).sendClose(1000, "").get(1, TimeUnit.SECONDS) }
    }

    companion object {
        const val WAIT_SECONDS = 10L
        const val ABNORMAL = 1006
    }
}

/** A raw TCP client for what a well-behaved client never sends. */
internal class RawSocket(
    port: Int,
) : AutoCloseable {
    private val socket = Socket("localhost", port).apply { soTimeout = TIMEOUT_MS }
    private val input: InputStream = socket.getInputStream()
    private val output: OutputStream = socket.getOutputStream()

    /** Writes [text] as it is and returns the first line of the answer. */
    fun sendRaw(text: String): String {
        output.write(text.toByteArray())
        output.flush()
        return readHead().lineSequence().first()
    }

    fun upgrade(): String {
        output.write(
            (
                "GET /ws HTTP/1.1\r\nHost: localhost\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                    "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n\r\n"
            ).toByteArray(),
        )
        output.flush()
        return readHead()
    }

    private fun readHead(): String {
        val head = StringBuilder()
        while (!head.endsWith("\r\n\r\n")) {
            val b = input.read()
            if (b < 0) break
            head.append(b.toChar())
        }
        return head.toString()
    }

    /** One masked client frame (opcode 1 text, 10 pong). */
    fun frame(
        opcode: Int,
        payload: ByteArray,
    ) {
        val mask = Random.nextBytes(MASK_BYTES)
        val header = ArrayList<Byte>()
        header += (FIN or opcode).toByte()
        when {
            payload.size < LEN_16 -> {
                header += (MASKED or payload.size).toByte()
            }

            else -> {
                header += (MASKED or LEN_16).toByte()
                header += (payload.size shr BYTE_BITS).toByte()
                header += payload.size.toByte()
            }
        }
        output.write(
            header.toByteArray() + mask + ByteArray(payload.size) { (payload[it].toInt() xor mask[it % MASK_BYTES].toInt()).toByte() },
        )
        output.flush()
    }

    fun text(text: String) = frame(OP_TEXT, text.encodeToByteArray())

    /** Reads server frames until the server closes the connection; the close code if a close frame came, else null. */
    fun closeCodeOrEnd(): Int? =
        try {
            var code: Int? = null
            while (true) {
                val first = input.read()
                if (first < 0) break
                val len = input.read() and LEN_MASK
                val body = input.readNBytes(if (len == LEN_16) (input.read() shl BYTE_BITS) or input.read() else len)
                if (first and OPCODE_MASK == OP_CLOSE &&
                    body.size >= 2
                ) {
                    code = ((body[0].toInt() and BYTE) shl BYTE_BITS) or (body[1].toInt() and BYTE)
                }
            }
            code
        } catch (_: java.net.SocketTimeoutException) {
            TIMED_OUT
        } catch (_: java.io.IOException) {
            null
        }

    override fun close() = socket.close()

    companion object {
        const val OP_TEXT = 1
        const val OP_PONG = 10
        const val OP_CLOSE = 8
        const val TIMED_OUT = -1
        private const val TIMEOUT_MS = 10_000
        private const val MASK_BYTES = 4
        private const val FIN = 0x80
        private const val MASKED = 0x80
        private const val LEN_16 = 126
        private const val LEN_MASK = 0x7f
        private const val OPCODE_MASK = 0x0f
        private const val BYTE = 0xff
        private const val BYTE_BITS = 8
    }
}

/** The members come from the in-process app; the sockets connect to a real Netty server over the same database. */
internal class SocketFixture(
    val delivery: DeliveryFixture,
    val port: Int,
    val databaseDown: AtomicBoolean,
)

/** The auth service's database, which fails while [down] is set (a blip or a flooded pool). */
private class FlakyDatabase(
    private val db: Database,
    private val down: AtomicBoolean,
) : TransactionRunner {
    override fun <T> transaction(block: (java.sql.Connection) -> T): T {
        check(!down.get()) { "database down" }
        return db.transaction(block)
    }
}

internal fun socketTest(
    settings: SocketSettings = SocketSettings(),
    hub: SocketHub = SocketHub(),
    block: suspend SocketFixture.() -> Unit,
) {
    val clock = TestClock()
    val url = TestPostgres.migratedDatabase()
    val origin = testConfig().publicOrigin
    Database.connect(DatabaseConfig(url, APP_ROLE, TestPostgres.appPasswordFile())).use { db ->
        val down = AtomicBoolean(false)
        val auth = AuthService(FlakyDatabase(db, down), origin, Challenges(clock), DbInviteRedeemer(), clock)
        val service = DeliveryService(db, clock, DeliveryLimits(clock), DeliveryRules(), hub)
        val endpoint = SocketEndpoint(auth, hub, Clock.systemUTC(), settings)
        val config = testConfig()
        val server =
            embeddedServer(
                Netty,
                configure = {
                    applyTo(this, config, endpoint)
                    connectors.clear()
                    connector { port = 0 }
                },
                module = {
                    module(
                        config,
                        AlwaysReady,
                        auth,
                        InviteService(db, origin, clock),
                        noDatabaseKeyPackages(),
                        noDatabaseMembers(),
                        service,
                    )
                },
            )
        server.start(wait = false)
        try {
            val port =
                runBlocking {
                    server.engine
                        .resolvedConnectors()
                        .single()
                        .port
                }
            testApplication {
                application {
                    module(
                        config,
                        AlwaysReady,
                        auth,
                        InviteService(db, origin, clock),
                        noDatabaseKeyPackages(),
                        noDatabaseMembers(),
                        service,
                    )
                }
                val client = createClient { defaultRequest { header(PROTOCOL_VERSION_HEADER, PROTOCOL_VERSION.toString()) } }
                SocketFixture(DeliveryFixture(db, client, clock, service), port, down).block()
            }
        } finally {
            server.stop(gracePeriodMillis = 0, timeoutMillis = 1_000)
        }
    }
}
