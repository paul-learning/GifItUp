package app.gifitup.server

import app.gifitup.protocol.HealthResponse
import app.gifitup.protocol.MIN_SUPPORTED_PROTOCOL_VERSION
import app.gifitup.protocol.PROTOCOL_VERSION
import app.gifitup.protocol.ProtocolJson
import app.gifitup.server.http.HEALTH_PATH
import app.gifitup.server.http.RequestDeadlineHandler
import app.gifitup.server.http.installErrorHandling
import app.gifitup.server.http.installProtocolVersionCheck
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.plugins.bodylimit.RequestBodyLimit
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing

fun main() {
    val config = ServerConfig.fromEnv(System.getenv())
    embeddedServer(
        Netty,
        configure = { applyTo(this, config) },
        module = { module(config) },
    ).start(wait = true)
}

/** Netty settings from [config]: the listening port and the limits peers that bypass Caddy can't lift (SEC-8). */
internal fun applyTo(
    engine: NettyApplicationEngine.Configuration,
    config: ServerConfig,
) {
    engine.connector {
        host = "0.0.0.0"
        port = config.port
    }
    with(config.limits) {
        engine.maxInitialLineLength = maxInitialLineLength
        engine.maxHeaderSize = maxHeaderSize
        engine.maxChunkSize = maxChunkSize
        // Ktor's read timeout only fails an active call; idle and half-sent connections stay open (SEC-15). Off, and
        // replaced by RequestDeadlineHandler, which closes them.
        engine.requestReadTimeoutSeconds = 0
        engine.responseWriteTimeoutSeconds = responseWriteTimeoutSeconds
        engine.channelPipelineConfig = {
            // HTTP/1 is the only pipeline (no TLS in the server, h2c off). Anything else is unexpected: close it.
            if (get(NETTY_HTTP_CODEC) != null) {
                addAfter(NETTY_HTTP_CODEC, "requestDeadline", RequestDeadlineHandler(requestDeadlineSeconds.toLong()))
            } else {
                channel().close()
            }
        }
    }
}

/** Name of the HTTP/1 codec in Ktor's Netty pipeline (`NettyChannelInitializer`, Ktor 3.6). */
private const val NETTY_HTTP_CODEC = "codec"

/** Composition root of the server (ADR-0004). */
fun Application.module(config: ServerConfig) {
    installErrorHandling()
    // Must stay the first interceptor after error handling: Plugins-phase interceptors run in install order, and an
    // application-wide plugin installed before it (auth, rate limits) would still run for rejected calls (#68 SEC-21).
    installProtocolVersionCheck()
    install(RequestBodyLimit) { bodyLimit { config.limits.defaultBodyLimitBytes } }
    install(ContentNegotiation) { json(ProtocolJson) }
    routing {
        get(HEALTH_PATH) {
            call.respond(
                HealthResponse(
                    status = "ok",
                    protocolVersion = PROTOCOL_VERSION,
                    minProtocolVersion = MIN_SUPPORTED_PROTOCOL_VERSION,
                ),
            )
        }
    }
}
