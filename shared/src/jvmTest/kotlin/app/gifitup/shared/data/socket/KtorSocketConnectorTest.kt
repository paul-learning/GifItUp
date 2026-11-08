package app.gifitup.shared.data.socket

import app.gifitup.shared.domain.events.SocketFrame
import app.gifitup.shared.domain.events.SocketLink
import app.gifitup.shared.domain.events.SocketOpening
import io.ktor.client.engine.java.Java
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KtorSocketConnectorTest {
    private fun connector() = KtorSocketConnector(Java.create())

    private suspend fun KtorSocketConnector.opened(
        server: RawSocketServer,
        hello: String = HELLO,
    ): SocketLink = assertIs<SocketOpening.Opened>(open(server.url, hello)).link

    @Test
    fun sendsTheHelloFirstAndReadsHintsAndTheCloseCode() =
        runBlocking<Unit> {
            val seen = CompletableFuture<String>()
            RawSocketServer { peer ->
                peer.accept(peer.readRequest())
                seen.complete(peer.readText())
                peer.sendText("""{"resync":true}""")
                peer.sendClose(4401)
                peer.awaitEnd()
            }.use { server ->
                connector().use { connector ->
                    val link = connector.opened(server)
                    assertEquals(HELLO, withTimeout(TIMEOUT) { seen.get() })
                    assertEquals("""{"resync":true}""", assertIs<SocketFrame.Text>(withTimeout(TIMEOUT) { link.receive() }).text)
                    assertEquals(4401, assertIs<SocketFrame.Closed>(withTimeout(TIMEOUT) { link.receive() }).code)
                    link.close()
                }
            }
        }

    @Test
    fun aFrameOverTheLimitEndsTheSocketAndIsNeverDelivered() =
        runBlocking<Unit> {
            RawSocketServer { peer ->
                peer.accept(peer.readRequest())
                peer.readText()
                peer.sendText("x".repeat(64 * 1024 * 1024))
                peer.awaitEnd()
            }.use { server ->
                connector().use { connector ->
                    val link = connector.opened(server)
                    assertIs<SocketFrame.Closed>(withTimeout(TIMEOUT) { link.receive() })
                }
            }
        }

    @Test
    fun aRedirectIsRefused() =
        runBlocking<Unit> {
            RawSocketServer { peer ->
                peer.readRequest()
                peer.redirect()
            }.use { server ->
                connector().use { connector ->
                    assertEquals(SocketOpening.Failed, connector.open(server.url, HELLO))
                }
            }
        }

    @Test
    fun aClosedPortIsAFailureThatCarriesNothing() =
        runBlocking<Unit> {
            val port = RawSocketServer { }.use { it.port }
            connector().use { connector ->
                assertEquals(SocketOpening.Failed, connector.open("ws://127.0.0.1:$port/ws", HELLO))
            }
        }

    @Test
    fun aLocalCloseEndsAWaitingReceiveWithNoCode() =
        runBlocking<Unit> {
            RawSocketServer { peer ->
                peer.accept(peer.readRequest())
                peer.readText()
                peer.awaitEnd()
            }.use { server ->
                connector().use { connector ->
                    val link = connector.opened(server)
                    val waiting = CompletableDeferred<SocketFrame>()
                    val job = async { waiting.complete(link.receive()) }
                    delay(300)
                    assertTrue(!waiting.isCompleted)
                    link.close()
                    val ended = withTimeout(TIMEOUT) { waiting.await() }
                    assertNull(assertIs<SocketFrame.Closed>(ended).code)
                    job.await()
                    link.close() // twice is fine
                }
            }
        }

    private companion object {
        const val HELLO = """{"v":1,"token":"SENTINEL-TOKEN"}"""
        const val TIMEOUT = 15_000L
    }
}
