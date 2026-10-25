package app.gifitup.server.delivery

import app.gifitup.protocol.MIN_SUPPORTED_PROTOCOL_VERSION
import app.gifitup.protocol.PROTOCOL_VERSION
import app.gifitup.protocol.ProtocolJson
import app.gifitup.protocol.SocketHello
import app.gifitup.server.auth.AuthService
import app.gifitup.server.auth.AuthenticatedSession
import app.gifitup.server.auth.KeyedRateLimiter
import io.netty.channel.ChannelFutureListener
import io.netty.channel.ChannelHandler
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.handler.codec.http.DefaultFullHttpResponse
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpMethod
import io.netty.handler.codec.http.HttpRequest
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.HttpVersion
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame
import io.netty.handler.codec.http.websocketx.PongWebSocketFrame
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame
import io.netty.handler.codec.http.websocketx.WebSocketDecoderConfig
import io.netty.handler.codec.http.websocketx.WebSocketFrame
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshakerFactory
import kotlinx.serialization.SerializationException
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.util.HexFormat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal const val SOCKET_PATH = "/ws"

/** Tunables of the realtime channel (ADR-0015); tests shorten the times. */
internal class SocketSettings(
    val helloTimeout: Duration = Duration.ofSeconds(HELLO_SECONDS),
    val pingInterval: Duration = Duration.ofSeconds(PING_SECONDS),
    val pongTimeout: Duration = Duration.ofSeconds(PONG_SECONDS),
    val maxFrameBytes: Int = MAX_FRAME_BYTES,
) {
    private companion object {
        const val HELLO_SECONDS = 5L
        const val PING_SECONDS = 25L
        const val PONG_SECONDS = 60L
        const val MAX_FRAME_BYTES = 256
    }
}

/** The key under which a session's sockets are found (logout closes exactly them). */
internal fun sessionKey(session: AuthenticatedSession): String =
    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(session.tokenHash))

/**
 * The realtime channel, built on Netty's own WebSocket handlers (already on the classpath through Ktor's Netty
 * engine, so there is no new dependency) and installed in the engine's pipeline, so it can bound frames, handshakes
 * and idle time before Ktor sees anything (ADR-0015). Authentication is the first frame; the URL carries nothing.
 */
internal class SocketEndpoint(
    val auth: AuthService,
    val hub: SocketHub,
    val clock: Clock,
    val settings: SocketSettings = SocketSettings(),
    private val upgrades: KeyedRateLimiter = KeyedRateLimiter(clock, UPGRADE_BURST, Duration.ofMillis(UPGRADE_REFILL_MS)),
) {
    /** The blocking work (session lookups) of all sockets, off the event loops. */
    val blocking: ExecutorService =
        Executors.newFixedThreadPool(BLOCKING_THREADS) { Thread(it, "socket-auth").apply { isDaemon = true } }

    /** A new upgrade handler for one connection. */
    fun upgradeHandler(): ChannelHandler = SocketUpgradeHandler(this)

    /** True if one more upgrade may start: the global bucket has a token and an unauthenticated place is free. */
    fun admit(): Boolean = upgrades.spend(UPGRADE_KEY) && hub.reserveUnauthenticated()

    private companion object {
        const val UPGRADE_BURST = 100
        const val UPGRADE_REFILL_MS = 50L
        const val UPGRADE_KEY = "upgrade"
        const val BLOCKING_THREADS = 4
    }
}

/**
 * Answers a WebSocket upgrade for `GET /ws` (exact path, no query) and hands every other request to Ktor, which
 * denies `/ws` without a session like any other path. After the handshake it installs [SocketSession] and leaves the
 * pipeline.
 */
internal class SocketUpgradeHandler(
    private val endpoint: SocketEndpoint,
) : ChannelInboundHandlerAdapter() {
    override fun channelRead(
        ctx: ChannelHandlerContext,
        msg: Any,
    ) {
        if (msg is HttpRequest && isUpgrade(msg)) begin(ctx, msg) else ctx.fireChannelRead(msg)
    }

    private fun isUpgrade(request: HttpRequest): Boolean =
        request.method() == HttpMethod.GET && request.uri() == SOCKET_PATH &&
            request.headers().containsValue(HttpHeaderNames.UPGRADE, "websocket", true)

    private fun begin(
        ctx: ChannelHandlerContext,
        request: HttpRequest,
    ) {
        if (!endpoint.admit()) {
            ctx
                .writeAndFlush(DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.SERVICE_UNAVAILABLE))
                .addListener(ChannelFutureListener.CLOSE)
            return
        }
        // The upgraded connection has no request deadline: the ping rule replaces it (#68 SEC-23).
        ctx.pipeline().get(REQUEST_DEADLINE)?.let { ctx.pipeline().remove(REQUEST_DEADLINE) }
        // The session handler is in place before the handshake, so a client's first frame can never beat it. It
        // releases the unauthenticated place when the connection ends, whatever happens next.
        ctx.pipeline().addAfter(ctx.name(), SESSION_HANDLER, SocketSession(endpoint))
        val config =
            WebSocketDecoderConfig
                .newBuilder()
                .maxFramePayloadLength(
                    endpoint.settings.maxFrameBytes,
                ).allowExtensions(false)
                .build()
        val handshaker = WebSocketServerHandshakerFactory("ws://localhost$SOCKET_PATH", null, config).newHandshaker(request)
        if (handshaker == null) {
            WebSocketServerHandshakerFactory.sendUnsupportedVersionResponse(ctx.channel()).addListener(ChannelFutureListener.CLOSE)
        } else {
            handshaker.handshake(ctx.channel(), request).addListener { future ->
                if (future.isSuccess) {
                    ctx.pipeline().remove(this)
                    // Ktor reads requests on demand (auto-read off); frames need the reads back on.
                    ctx.channel().config().isAutoRead = true
                    ctx.channel().read()
                } else {
                    ctx.close()
                }
            }
        }
    }

    private companion object {
        const val REQUEST_DEADLINE = "requestDeadline"
        const val SESSION_HANDLER = "socketSession"
    }
}

private fun parseHello(text: String): SocketHello? =
    try {
        ProtocolJson.decodeFromString<SocketHello>(text)
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

/** The state of one socket: waiting for the hello, checking it, or online. */
private enum class Phase { AWAITING_HELLO, AUTHENTICATING, ONLINE, CLOSED }

/**
 * One socket after the handshake (ADR-0015). The first frame must be the hello within the timeout; after it the only
 * frame a client may send is a pong. Everything else closes the socket. Runs on the channel's event loop; session
 * lookups run on [SocketEndpoint.blocking].
 */
internal class SocketSession(
    private val endpoint: SocketEndpoint,
) : SimpleChannelInboundHandler<WebSocketFrame>() {
    private var phase = Phase.AWAITING_HELLO
    private var token: String? = null
    private var socket: ClientSocket? = null
    private var lastPong = endpoint.clock.instant()
    private var helloTimer: ScheduledFuture<*>? = null
    private var pingTimer: ScheduledFuture<*>? = null
    private val placeHeld = AtomicBoolean(true)

    override fun handlerAdded(ctx: ChannelHandlerContext) {
        helloTimer =
            ctx.executor().schedule(
                { close(ctx, SocketClose.BAD_HANDSHAKE) },
                endpoint.settings.helloTimeout.toMillis(),
                TimeUnit.MILLISECONDS,
            )
    }

    override fun channelRead0(
        ctx: ChannelHandlerContext,
        frame: WebSocketFrame,
    ) {
        when {
            frame is CloseWebSocketFrame -> ctx.writeAndFlush(frame.retain()).addListener(ChannelFutureListener.CLOSE)
            phase == Phase.AWAITING_HELLO && frame is TextWebSocketFrame && frame.isFinalFragment -> hello(ctx, frame.text())
            phase == Phase.ONLINE && frame is PongWebSocketFrame -> lastPong = endpoint.clock.instant()
            else -> close(ctx, SocketClose.BAD_HANDSHAKE)
        }
    }

    private fun hello(
        ctx: ChannelHandlerContext,
        text: String,
    ) {
        phase = Phase.AUTHENTICATING
        val parsed = parseHello(text)
        when {
            parsed == null -> close(ctx, SocketClose.BAD_HANDSHAKE)
            parsed.v < MIN_SUPPORTED_PROTOCOL_VERSION -> close(ctx, SocketClose.CLIENT_OUTDATED)
            parsed.v > PROTOCOL_VERSION -> close(ctx, SocketClose.SERVER_OUTDATED)
            else -> authenticate(ctx, parsed.token)
        }
    }

    private fun authenticate(
        ctx: ChannelHandlerContext,
        presented: String,
    ) {
        endpoint.blocking.execute {
            val session = runCatching { endpoint.auth.authenticate(presented) }.getOrNull()
            ctx.executor().execute { online(ctx, presented, session) }
        }
    }

    private fun online(
        ctx: ChannelHandlerContext,
        presented: String,
        session: AuthenticatedSession?,
    ) {
        val client =
            session?.takeIf { phase == Phase.AUTHENTICATING }?.let {
                ClientSocket(
                    it.record.deviceId,
                    sessionKey(it),
                    ctx.channel(),
                )
            }
        when {
            phase != Phase.AUTHENTICATING -> {
                Unit
            }

            session == null || client == null -> {
                close(ctx, SocketClose.UNAUTHORIZED)
            }

            !endpoint.auth.requestBudget.spend(session) || !endpoint.hub.attach(client).also { placeHeld.set(false) } -> {
                close(ctx, SocketClose.TRY_LATER)
            }

            else -> {
                token = presented
                socket = client
                phase = Phase.ONLINE
                helloTimer?.cancel(false)
                lastPong = endpoint.clock.instant()
                val every = endpoint.settings.pingInterval.toMillis()
                pingTimer = ctx.executor().scheduleWithFixedDelay({ tick(ctx) }, every, every, TimeUnit.MILLISECONDS)
            }
        }
    }

    /** Every ping interval: close a socket that has not answered for the pong timeout, else ping and re-check the session. */
    private fun tick(ctx: ChannelHandlerContext) {
        val silent = Duration.between(lastPong, endpoint.clock.instant())
        if (silent > endpoint.settings.pongTimeout) {
            close(ctx, SocketClose.TIMEOUT)
        } else {
            ctx.writeAndFlush(PingWebSocketFrame())
            val presented = token
            if (presented != null) {
                endpoint.blocking.execute {
                    val alive = runCatching { endpoint.auth.authenticate(presented) }.getOrNull() != null
                    if (!alive) ctx.executor().execute { close(ctx, SocketClose.UNAUTHORIZED) }
                }
            }
        }
    }

    override fun channelWritabilityChanged(ctx: ChannelHandlerContext) {
        if (ctx.channel().isWritable && socket?.resyncDue?.compareAndSet(true, false) == true) {
            ctx.writeAndFlush(TextWebSocketFrame(RESYNC))
        }
        ctx.fireChannelWritabilityChanged()
    }

    override fun channelInactive(ctx: ChannelHandlerContext) {
        phase = Phase.CLOSED
        helloTimer?.cancel(false)
        pingTimer?.cancel(false)
        socket?.let(endpoint.hub::detach)
        if (placeHeld.getAndSet(false)) endpoint.hub.releaseUnauthenticated()
        ctx.fireChannelInactive()
    }

    override fun exceptionCaught(
        ctx: ChannelHandlerContext,
        cause: Throwable,
    ) {
        // A decoder error (an oversize frame, a protocol violation) or a reset: close, say nothing about it.
        ctx.close()
    }

    private fun close(
        ctx: ChannelHandlerContext,
        why: SocketClose,
    ) {
        phase = Phase.CLOSED
        ctx.channel().closeWith(why)
    }

    private companion object {
        val RESYNC = ProtocolJson.encodeToString(app.gifitup.protocol.SocketResync(true))
    }
}
