package app.gifitup.server

import app.gifitup.protocol.HealthResponse
import app.gifitup.protocol.MIN_SUPPORTED_PROTOCOL_VERSION
import app.gifitup.protocol.PROTOCOL_VERSION
import app.gifitup.protocol.ProtocolJson
import app.gifitup.server.http.HEALTH_PATH
import app.gifitup.server.http.ProtocolVersionCheck
import app.gifitup.server.http.installErrorHandling
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
        engine.requestReadTimeoutSeconds = requestReadTimeoutSeconds
        engine.responseWriteTimeoutSeconds = responseWriteTimeoutSeconds
    }
}

/** Composition root of the server (ADR-0004). */
fun Application.module(config: ServerConfig) {
    installErrorHandling()
    install(ProtocolVersionCheck)
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
