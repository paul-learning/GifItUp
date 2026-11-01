package app.gifitup.server

import app.gifitup.protocol.HealthResponse
import app.gifitup.protocol.MIN_SUPPORTED_PROTOCOL_VERSION
import app.gifitup.protocol.PROTOCOL_VERSION
import app.gifitup.protocol.ProtocolJson
import app.gifitup.server.auth.AuthService
import app.gifitup.server.auth.Challenges
import app.gifitup.server.auth.DbInviteRedeemer
import app.gifitup.server.auth.InviteCleanup
import app.gifitup.server.auth.PUBLIC_PATHS
import app.gifitup.server.auth.authRoutes
import app.gifitup.server.auth.installSessionAuth
import app.gifitup.server.config.GifProviderEndpoint
import app.gifitup.server.config.GifProviderKey
import app.gifitup.server.config.gifProviderRoutes
import app.gifitup.server.db.Database
import app.gifitup.server.db.DatabaseConfig
import app.gifitup.server.db.DeliveryCleanup
import app.gifitup.server.db.KeyPackageCleanup
import app.gifitup.server.delivery.DeliveryLimits
import app.gifitup.server.delivery.DeliveryService
import app.gifitup.server.delivery.SocketEndpoint
import app.gifitup.server.delivery.deliveryBodyLimit
import app.gifitup.server.delivery.deliveryRoutes
import app.gifitup.server.http.CachedReadiness
import app.gifitup.server.http.HEALTH_PATH
import app.gifitup.server.http.ReadinessProbe
import app.gifitup.server.http.RequestDeadlineHandler
import app.gifitup.server.http.installErrorHandling
import app.gifitup.server.http.installProtocolVersionCheck
import app.gifitup.server.invites.InviteService
import app.gifitup.server.invites.inviteRoutes
import app.gifitup.server.keypackages.KeyPackageLimits
import app.gifitup.server.keypackages.KeyPackageService
import app.gifitup.server.keypackages.keyPackageBodyLimit
import app.gifitup.server.keypackages.keyPackageRoutes
import app.gifitup.server.members.MemberService
import app.gifitup.server.members.memberRoutes
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
    // Registration needs a valid invite (ADR-0010); the first one comes from the admin CLI.
    val auth = AuthService(database, config.publicOrigin, Challenges(clock), DbInviteRedeemer(), clock)
    val invites = InviteService(database, config.publicOrigin, clock)
    val keyPackages = KeyPackageService(database, clock, KeyPackageLimits(clock))
    val members = MemberService(database)
    val delivery = DeliveryService(database, clock, DeliveryLimits(clock))
    // The GIF provider's key comes from a secret file, never from the config object that may be logged (ADR-0003, #40).
    val gifProvider = GifProviderEndpoint.of(GifProviderKey.fromEnv(env), clock)
    val inviteCleanup = InviteCleanup(database, clock).start()
    val keyPackageCleanup = KeyPackageCleanup(database, clock).start()
    val deliveryCleanup = DeliveryCleanup(database, clock).start()
    val sockets = SocketEndpoint(auth, delivery.sockets, clock)
    val server =
        embeddedServer(
            Netty,
            configure = { applyTo(this, config, sockets) },
            module = { module(config, readiness, auth, invites, keyPackages, members, delivery, gifProvider) },
        )
    server.monitor.subscribe(ApplicationStopped) {
        inviteCleanup.shutdownNow()
        keyPackageCleanup.shutdownNow()
        deliveryCleanup.shutdownNow()
        sockets.shutdown()
        inviteCleanup.awaitTermination(CLEANUP_SHUTDOWN_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
        keyPackageCleanup.awaitTermination(CLEANUP_SHUTDOWN_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
        deliveryCleanup.awaitTermination(CLEANUP_SHUTDOWN_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
        database.close()
    }
    server.start(wait = true)
}

/** Netty settings from [config]: the listening address and port, and the limits peers that bypass Caddy can't lift (SEC-8). */
internal fun applyTo(
    engine: NettyApplicationEngine.Configuration,
    config: ServerConfig,
    sockets: SocketEndpoint? = null,
) {
    engine.connector {
        host = config.host
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
                // The realtime channel answers its own upgrade before Ktor sees the request (ADR-0015).
                sockets?.let { addAfter("requestDeadline", "socketUpgrade", it.upgradeHandler()) }
            } else {
                channel().close()
            }
        }
    }
}

/** Name of the HTTP/1 codec in Ktor's Netty pipeline (`NettyChannelInitializer`, Ktor 3.6). */
private const val NETTY_HTTP_CODEC = "codec"

/** Composition root of the server (ADR-0004). */
@Suppress("LongParameterList") // One parameter per service.
fun Application.module(
    config: ServerConfig,
    readiness: ReadinessProbe,
    auth: AuthService,
    invites: InviteService,
    keyPackages: KeyPackageService,
    members: MemberService,
    delivery: DeliveryService,
    gifProvider: GifProviderEndpoint = GifProviderEndpoint.unconfigured(),
) = configure(config, readiness, auth, invites, keyPackages, members, delivery, gifProvider, PUBLIC_PATHS)

/** [module] with the public paths as a parameter, so tests can make their test-only routes public (#73 CR-5). */
@Suppress("LongParameterList") // The composition root: one parameter per service.
internal fun Application.configure(
    config: ServerConfig,
    readiness: ReadinessProbe,
    auth: AuthService,
    invites: InviteService,
    keyPackages: KeyPackageService,
    members: MemberService,
    delivery: DeliveryService,
    gifProvider: GifProviderEndpoint,
    publicPaths: Set<String>,
) {
    installErrorHandling()
    // Must stay the first interceptor after error handling: Plugins-phase interceptors run in install order, and an
    // application-wide plugin installed before it (auth, rate limits) would still run for rejected calls (#68 SEC-21).
    installProtocolVersionCheck()
    // Second: deny by default. Every path but the public ones needs a session before routing runs (ADR-0009).
    installSessionAuth(auth, publicPaths)
    install(RequestBodyLimit) {
        bodyLimit { call -> keyPackageBodyLimit(call, deliveryBodyLimit(call, config.limits.defaultBodyLimitBytes)) }
    }
    install(ContentNegotiation) { json(ProtocolJson) }
    routing {
        authRoutes(auth, delivery::sessionEnded)
        inviteRoutes(invites)
        keyPackageRoutes(keyPackages)
        memberRoutes(members)
        gifProviderRoutes(gifProvider)
        deliveryRoutes(delivery)
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

private const val CLEANUP_SHUTDOWN_SECONDS = 5L
