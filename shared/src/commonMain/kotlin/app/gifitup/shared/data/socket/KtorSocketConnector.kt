package app.gifitup.shared.data.socket

import app.gifitup.shared.domain.events.SocketConnector
import app.gifitup.shared.domain.events.SocketFrame
import app.gifitup.shared.domain.events.SocketLink
import app.gifitup.shared.domain.events.SocketOpening
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readReason
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlin.concurrent.Volatile

/**
 * Opens the push channel's socket (ADR-0015) over one injected engine. The engine decides how TLS and the trust store work;
 * this class adds nothing to it (no trust manager, no pinning). Hardening, all of it checked by tests:
 * - frames over [MAX_FRAME_BYTES] end the socket (the library closes with 1009), so a hostile server cannot make this device
 *   buffer big frames: a real hint is under 120 bytes;
 * - redirects are not followed and only a connect timeout is set (a quiet socket is normal: the server pings every 25 s);
 * - no logging plugin is installed, and no failure's text leaves this class: the hello holds the session token and library
 *   exceptions can quote what they were doing.
 */
class KtorSocketConnector(
    private val engine: HttpClientEngine,
) : SocketConnector,
    AutoCloseable {
    private val client =
        HttpClient(engine) {
            expectSuccess = false
            followRedirects = false
            install(HttpTimeout) { connectTimeoutMillis = CONNECT_TIMEOUT_MS }
            install(WebSockets) {
                maxFrameSize = MAX_FRAME_BYTES
                // The server pings; the client only answers (the server closes a socket whose client pings).
                pingIntervalMillis = 0
            }
        }

    @Suppress("TooGenericExceptionCaught") // Any failure of opening is "could not connect"; its text could quote the hello.
    override suspend fun open(
        url: String,
        hello: String,
    ): SocketOpening {
        var session: DefaultClientWebSocketSession? = null
        return try {
            val opened = client.webSocketSession(url)
            session = opened
            opened.send(Frame.Text(hello))
            SocketOpening.Opened(KtorSocketLink(opened))
        } catch (e: CancellationException) {
            session?.close()
            throw e
        } catch (_: Throwable) {
            runCatching { session?.close() }
            SocketOpening.Failed
        }
    }

    override fun close() {
        client.close()
    }

    private class KtorSocketLink(
        private val session: DefaultClientWebSocketSession,
    ) : SocketLink {
        @Volatile private var closedHere = false

        override suspend fun receive(): SocketFrame {
            var next: SocketFrame? = null
            while (next == null) next = read()
            return next
        }

        /** One frame as the loop sees it, or `null` for a frame kind that is not part of the protocol. */
        private suspend fun read(): SocketFrame? {
            val frame = nextFrame()
            return when {
                frame == null -> SocketFrame.Closed(if (closedHere) null else closeCode())
                frame is Frame.Text -> SocketFrame.Text(frame.readText())
                frame is Frame.Close -> SocketFrame.Closed(frame.readReason()?.code?.toInt())
                else -> null // Binary frames are not part of the protocol; pings and pongs are the library's.
            }
        }

        /** The next frame, `null` once the channel is closed; a failure inside is the end, not an error. */
        @Suppress("TooGenericExceptionCaught")
        private suspend fun nextFrame(): Frame? =
            try {
                session.incoming.receiveCatching().getOrNull()
            } catch (e: CancellationException) {
                // A local close cancels the session, not the caller: that is the end, not a cancellation.
                if (session.coroutineContext.isActive) throw e
                null
            } catch (_: Throwable) {
                null
            }

        private suspend fun closeCode(): Int? =
            runCatching {
                session.closeReason
                    .await()
                    ?.code
                    ?.toInt()
            }.getOrNull()

        override suspend fun close() {
            closedHere = true
            runCatching { session.close(CloseReason(CloseReason.Codes.NORMAL, "")) }
            session.cancel()
        }
    }

    companion object {
        const val CONNECT_TIMEOUT_MS = 10_000L

        /** A real hint is under 120 bytes; the loop drops anything over 1024 characters anyway. */
        const val MAX_FRAME_BYTES = 1024L
    }
}
