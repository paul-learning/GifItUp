package app.gifitup.server.delivery

import app.gifitup.protocol.SocketCloseCodes
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The realtime channel against the real Netty engine (ADR-0015): handshake, pushes, limits, idle handling. */
class DeliverySocketTest {
    private fun fast() =
        SocketSettings(helloTimeout = Duration.ofMillis(500), pingInterval = Duration.ofMillis(100), pongTimeout = Duration.ofMillis(400))

    @Test
    fun anAuthenticatedSocketGetsHintsForNewMessagesAndWelcomes() =
        socketTest {
            val ana = delivery.member("ana")
            val ben = delivery.member("ben")
            val benSocket = SocketClient(port).also { it.hello(ben.token) }
            val group = delivery.newGroupId()
            delivery.posted(delivery.create(ana, group, delivery.commit(0, added = listOf(ben))))
            val welcome = assertNotNull(benSocket.next())
            assertTrue("\"kind\":\"welcome\"" in welcome && group in welcome, welcome)

            val anaSocket = SocketClient(port).also { it.hello(ana.token) }
            val sent = delivery.posted(delivery.post(ana, group, delivery.application()))
            val hint = "\"seq\":${sent.seq},\"kind\":\"application\""
            assertTrue(hint in assertNotNull(benSocket.next()))
            assertTrue(hint in assertNotNull(anaSocket.next()), "the sender's own devices hear it too")
            benSocket.close()
            anaSocket.close()
        }

    @Test
    fun aBadTokenAWrongVersionOrJunkCloseWithTheirOwnCodes() =
        socketTest {
            val ana = delivery.member("ana")
            val bad = SocketClient(port).also { it.hello("x".repeat(43)) }
            assertEquals(SocketCloseCodes.UNAUTHORIZED, bad.closeCode())
            val old = SocketClient(port).also { it.hello(ana.token, version = 0) }
            assertEquals(SocketCloseCodes.CLIENT_OUTDATED, old.closeCode())
            val newer = SocketClient(port).also { it.hello(ana.token, version = 99) }
            assertEquals(SocketCloseCodes.SERVER_OUTDATED, newer.closeCode())
            val junk = SocketClient(port).also { it.send("not json") }
            assertEquals(SocketCloseCodes.BAD_HANDSHAKE, junk.closeCode())
        }

    @Test
    fun theUrlCarriesNothingAndOnlyTheExactPathUpgrades() =
        socketTest {
            val ana = delivery.member("ana")
            val http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()
            val withQuery = SocketClient(port, path = "/ws?token=${ana.token}")
            assertTrue(withQuery.failedToConnect(), "a query string is not the socket")
            assertTrue(SocketClient(port, path = "/ws/").failedToConnect(), "a trailing slash is not the socket")
            val plain =
                http.send(
                    HttpRequest
                        .newBuilder(URI("http://localhost:$port/ws"))
                        .header("GifItUp-Protocol", "1")
                        .GET()
                        .build(),
                    HttpResponse.BodyHandlers.discarding(),
                )
            assertEquals(401, plain.statusCode(), "without an upgrade it is an ordinary path and needs a session")
        }

    @Test
    fun noHelloInTimeAFrameAfterTheHelloOrAnOversizeFrameCloseTheSocket() =
        socketTest(fast()) {
            val ana = delivery.member("ana")
            RawSocket(port).use { silent ->
                assertTrue(silent.upgrade().startsWith("HTTP/1.1 101"))
                assertEquals(SocketCloseCodes.BAD_HANDSHAKE, silent.closeCodeOrEnd(), "no hello within the timeout")
            }
            RawSocket(port).use { chatty ->
                chatty.upgrade()
                chatty.text("""{"v":1,"token":"${ana.token}"}""")
                chatty.text("hello again")
                assertEquals(SocketCloseCodes.BAD_HANDSHAKE, chatty.closeCodeOrEnd(), "only pongs are allowed after the hello")
            }
            RawSocket(port).use { big ->
                big.upgrade()
                big.text("a".repeat(300))
                val end = big.closeCodeOrEnd()
                assertTrue(end != RawSocket.TIMED_OUT, "an oversize frame ends the connection: $end")
            }
        }

    @Test
    fun aSocketThatDoesNotAnswerPingsIsClosedAndOneThatDoesStaysOpen() =
        socketTest(fast()) {
            val ana = delivery.member("ana")
            val ben = delivery.member("ben")
            val polite = SocketClient(port).also { it.hello(ben.token) } // the JDK client answers pings itself
            RawSocket(port).use { deaf ->
                deaf.upgrade()
                deaf.text("""{"v":1,"token":"${ana.token}"}""")
                assertEquals(1001, deaf.closeCodeOrEnd(), "no pong within the timeout")
            }
            Thread.sleep(1_000)
            assertFalse(polite.closed.isDone, "a client that answers stays online")
            polite.close()
        }

    @Test
    fun logoutAndAnEndedSessionCloseTheSocket() =
        socketTest(fast()) {
            val ana = delivery.member("ana")
            val socket = SocketClient(port).also { it.hello(ana.token) }
            Thread.sleep(300)
            val response = delivery.client.post("/auth/logout") { bearerAuth(ana.token) }
            assertEquals(HttpStatusCode.NoContent, response.status)
            assertEquals(SocketCloseCodes.UNAUTHORIZED, socket.closeCode())
        }

    @Test
    fun theCapsRefuseUnauthenticatedSocketsAndEvictTheOldestOfADevice() =
        socketTest(hub = SocketHub(maxUnauthenticated = 2, maxAuthenticated = 500, maxPerDevice = 2)) {
            val ana = delivery.member("ana")
            val a = RawSocket(port).also { it.upgrade() }
            val b = RawSocket(port).also { it.upgrade() }
            val refused = RawSocket(port)
            assertTrue(refused.upgrade().startsWith("HTTP/1.1 503"), "the third unauthenticated socket has no place")
            refused.close()
            a.close()
            b.close()

            val first = SocketClient(port).also { it.hello(ana.token) }
            val second = SocketClient(port).also { it.hello(ana.token) }
            Thread.sleep(300)
            val third = SocketClient(port).also { it.hello(ana.token) }
            assertEquals(1008, first.closeCode(), "the oldest socket of the device is replaced")
            assertFalse(second.closed.isDone)
            third.close()
            second.close()
        }

    @Test
    fun aFailingSessionLookupIsNotASessionThatEnded() =
        socketTest(fast()) {
            val ana = delivery.member("ana")
            val online = SocketClient(port).also { it.hello(ana.token) }
            Thread.sleep(300)
            databaseDown.set(true)
            val refused = SocketClient(port).also { it.hello(ana.token) }
            assertEquals(1013, refused.closeCode(), "at the hello: try again later, not 4401")
            Thread.sleep(500) // several pings with the lookup failing: the online socket stays
            assertFalse(online.closed.isDone, "a failing re-check keeps the socket")
            databaseDown.set(false)
            val again = SocketClient(port).also { it.hello(ana.token) }
            Thread.sleep(300)
            assertFalse(again.closed.isDone)
            again.close()
            online.close()
        }

    @Test
    fun anUnsolicitedPongClosesTheSocket() =
        socketTest(
            SocketSettings(
                helloTimeout = Duration.ofSeconds(5),
                pingInterval = Duration.ofSeconds(30),
                pongTimeout = Duration.ofSeconds(60),
            ),
        ) {
            val ana = delivery.member("ana")
            RawSocket(port).use { raw ->
                raw.upgrade()
                raw.text("""{"v":1,"token":"${ana.token}"}""")
                Thread.sleep(300)
                raw.frame(RawSocket.OP_PONG, ByteArray(0))
                assertEquals(SocketCloseCodes.BAD_HANDSHAKE, raw.closeCodeOrEnd())
            }
        }

    @Test
    fun anUpgradeWithoutAKeyIsRefusedAndAPipelinedUpgradeIsAnsweredOrOvertakes() =
        socketTest {
            RawSocket(port).use { raw ->
                val status =
                    raw.sendRaw(
                        "GET /ws HTTP/1.1\r\nHost: localhost\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                            "Sec-WebSocket-Version: 13\r\n\r\n",
                    )
                assertTrue(status.startsWith("HTTP/1.1 400"), status)
            }
            RawSocket(port).use { raw ->
                val both =
                    raw.sendRaw(
                        "GET /health HTTP/1.1\r\nHost: localhost\r\n\r\n" +
                            "GET /ws HTTP/1.1\r\nHost: localhost\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                            "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n\r\n",
                    )
                // The upgrade may overtake the pipelined request's answer (the client's own connection, nobody else's); either way
                // the server answers and does not hang or crash.
                assertTrue(both.startsWith("HTTP/1.1 200") || both.startsWith("HTTP/1.1 101"), both)
            }
        }
}
