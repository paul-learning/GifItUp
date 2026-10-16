package app.gifitup.server

import app.gifitup.protocol.HealthResponse
import app.gifitup.protocol.MIN_SUPPORTED_PROTOCOL_VERSION
import app.gifitup.protocol.PROTOCOL_VERSION
import app.gifitup.protocol.ProtocolJson
import app.gifitup.server.auth.AuthService
import app.gifitup.server.auth.Challenges
import app.gifitup.server.auth.DenyAllInviteRedeemer
import app.gifitup.server.auth.PUBLIC_PATHS
import app.gifitup.server.auth.authRoutes
import app.gifitup.server.auth.installSessionAuth
import app.gifitup.server.db.Database
import app.gifitup.server.db.DatabaseConfig
import app.gifitup.server.http.CachedReadiness
import app.gifitup.server.http.HEALTH_PATH
import app.gifitup.server.http.ReadinessProbe
import app.gifitup.server.http.RequestDeadlineHandler
import app.gifitup.server.http.installErrorHandling
import app.gifitup.server.http.installProtocolVersionCheck
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
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
import java.time.Clock

fun main() {
    val env = System.getenv()
    val config = ServerConfig.fromEnv(env)
    // Fails startup if the database is unreachable or the app role's credentials are wrong; Docker restarts us.
    val database = Database.connect(DatabaseConfig.fromEnv(env))
    val clock = Clock.systemUTC()
    val readiness = CachedReadiness(clock) { database.isReachable() }
    // Registration stays closed (deny-all invites) until #13 brings real invite codes.
    val auth = AuthService(database, config.publicOrigin, Challenges(clock), DenyAllInviteRedeemer, clock)
    val server =
        embeddedServer(
            Netty,
            configure = { applyTo(this, config) },
            module = { module(config, readiness, auth) },
        )
    server.monitor.subscribe(ApplicationStopped) { database.close() }
    server.start(wait = true)
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
fun Application.module(
    config: ServerConfig,
    readiness: ReadinessProbe,
    auth: AuthService,
    publicPaths: Set<String> = PUBLIC_PATHS,
) {
    installErrorHandling()
    // Must stay the first interceptor after error handling: Plugins-phase interceptors run in install order, and an
    // application-wide plugin installed before it (auth, rate limits) would still run for rejected calls (#68 SEC-21).
    installProtocolVersionCheck()
    // Second: deny by default. Every path but the public ones needs a session before routing runs (ADR-0009).
    installSessionAuth(auth, publicPaths)
    install(RequestBodyLimit) { bodyLimit { config.limits.defaultBodyLimitBytes } }
    install(ContentNegotiation) { json(ProtocolJson) }
    routing {
        authRoutes(auth)
        // 503 when the database is unreachable, so deploy.sh's health gate covers it and rolls back (ADR-0008).
        get(HEALTH_PATH) {
            val ready = readiness.isReady()
            call.respond(
                if (ready) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable,
                HealthResponse(
                    status = if (ready) "ok" else "unavailable",
                    protocolVersion = PROTOCOL_VERSION,
                    minProtocolVersion = MIN_SUPPORTED_PROTOCOL_VERSION,
                ),
            )
        }
    }
}
