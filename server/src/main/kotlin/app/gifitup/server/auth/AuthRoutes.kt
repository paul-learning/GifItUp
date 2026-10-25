package app.gifitup.server.auth

import app.gifitup.protocol.ApiErrorCode
import app.gifitup.protocol.ChallengeResponse
import app.gifitup.protocol.DeviceInfo
import app.gifitup.protocol.DeviceListResponse
import app.gifitup.protocol.ErrorResponse
import app.gifitup.protocol.LoginRequest
import app.gifitup.protocol.RegisterRequest
import app.gifitup.protocol.SessionResponse
import app.gifitup.server.http.HEALTH_PATH
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.util.AttributeKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal const val CHALLENGE_PATH = "/auth/challenge"
internal const val REGISTER_PATH = "/accounts"
internal const val LOGIN_PATH = "/auth/login"
internal const val LOGOUT_PATH = "/auth/logout"
internal const val DEVICES_PATH = "/devices"

/** The only paths reachable without a session (ADR-0009). Everything else, unknown paths included, needs one. */
internal val PUBLIC_PATHS = setOf(HEALTH_PATH, CHALLENGE_PATH, REGISTER_PATH, LOGIN_PATH)

/**
 * One route exempt from the account budget: an exact [method] and a whole-path [path] pattern, so a templated route
 * fits (`Regex("/devices/[^/]+")` for `DELETE /devices/{id}`) and another method on the same path doesn't (#77
 * SEC-70, SEC-71).
 */
internal class BudgetExemption(
    private val method: HttpMethod,
    private val path: Regex,
) {
    fun matches(
        method: HttpMethod,
        path: String,
    ): Boolean = method == this.method && this.path.matches(path)
}

/**
 * Authenticated routes that don't spend the account budget (#76 SEC-65): the ones that end a session or revoke a
 * device. Otherwise one session draining the account's budget (say, a stolen one) would also block those calls.
 * Today that only means every device can still log itself out: logout ends the calling session only, so a stolen
 * session can't be ended through the API yet. The protection that matters comes with device revocation
 * (`DELETE /devices/{id}`, #72), which belongs here too, tested through the real route. They limit themselves
 * (logout destroys the calling session).
 */
internal val BUDGET_EXEMPTIONS = listOf(BudgetExemption(HttpMethod.Post, Regex(Regex.escape(LOGOUT_PATH))))

private val SessionKey = AttributeKey<AuthenticatedSession>("gifitup.session")

/** The session of an authenticated call. Only present on non-public paths, where the interceptor guarantees it. */
internal val ApplicationCall.session: AuthenticatedSession get() = attributes[SessionKey]

/**
 * Deny by default (#68 round 0, SEC-6): every request except those to [publicPaths] needs `Authorization: Bearer
 * <token>` with a live session, checked before routing. A new route is protected without opting in, and an unknown path
 * answers 401, not 404, to a caller without a session. Installed directly after the protocol version check, so
 * no route-scoped plugin runs for a rejected call (#68 SEC-16, SEC-21). The token is never logged.
 *
 * Then the account's request budget (#76): a call over it gets `429 rate_limited` and never reaches its route. Every
 * authenticated HTTP request counts, except [budgetExemptions]. Nothing is logged per throttled request.
 */
internal fun Application.installSessionAuth(
    auth: AuthService,
    publicPaths: Set<String> = PUBLIC_PATHS,
    budgetExemptions: List<BudgetExemption> = BUDGET_EXEMPTIONS,
) {
    intercept(ApplicationCallPipeline.Plugins) {
        val path = call.request.path()
        if (path in publicPaths) return@intercept
        val header =
            call.request.headers
                .getAll(HttpHeaders.Authorization)
                ?.singleOrNull()
        val token = header?.takeIf { it.startsWith(BEARER) }?.removePrefix(BEARER)
        val session = token?.let { withContext(Dispatchers.IO) { auth.authenticate(it) } }
        when {
            session == null -> {
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse(ApiErrorCode.UNAUTHORIZED))
                finish()
            }

            budgetExemptions.none { it.matches(call.request.httpMethod, path) } && !auth.requestBudget.spend(session) -> {
                call.respond(HttpStatusCode.TooManyRequests, ErrorResponse(ApiErrorCode.RATE_LIMITED))
                finish()
            }

            else -> {
                call.attributes.put(SessionKey, session)
            }
        }
    }
}

private const val BEARER = "Bearer "

/** The auth endpoints (ADR-0009). Blocking work (JDBC, signature checks) runs on the IO dispatcher. */
internal fun Route.authRoutes(
    auth: AuthService,
    onLogout: (AuthenticatedSession) -> Unit = {},
) {
    post(CHALLENGE_PATH) {
        when (val outcome = auth.challenge()) {
            is AuthOutcome.Ok -> call.respond(ChallengeResponse(outcome.value))
            is AuthOutcome.Failed -> call.respondError(outcome.error)
        }
    }
    post(REGISTER_PATH) {
        val request = call.receive<RegisterRequest>()
        val outcome =
            withContext(Dispatchers.IO) {
                with(request) { auth.register(username, inviteCode, signaturePublicKey, challenge, signature) }
            }
        call.respondSession(outcome, HttpStatusCode.Created)
    }
    post(LOGIN_PATH) {
        val request = call.receive<LoginRequest>()
        val outcome = withContext(Dispatchers.IO) { auth.login(request.deviceId, request.challenge, request.signature) }
        call.respondSession(outcome, HttpStatusCode.OK)
    }
    post(LOGOUT_PATH) {
        withContext(Dispatchers.IO) { auth.logout(call.session) }
        onLogout(call.session)
        call.respond(HttpStatusCode.NoContent)
    }
    get(DEVICES_PATH) {
        val devices = withContext(Dispatchers.IO) { auth.devices(call.session) }
        call.respond(
            DeviceListResponse(
                devices.map { DeviceInfo(it.id.toString(), it.createdAt.epochSecond, it.lastSeenAt.epochSecond) },
            ),
        )
    }
}

private suspend fun ApplicationCall.respondSession(
    outcome: AuthOutcome<IssuedSession>,
    success: HttpStatusCode,
) = when (outcome) {
    is AuthOutcome.Ok -> {
        // The body carries a session token: no cache may keep it (#13 SEC-48).
        response.headers.append(HttpHeaders.CacheControl, "no-store")
        respond(success, with(outcome.value) { SessionResponse(deviceId.toString(), token, expiresAt.epochSecond) })
    }

    is AuthOutcome.Failed -> {
        respondError(outcome.error)
    }
}

private suspend fun ApplicationCall.respondError(error: AuthError) {
    val (status, code) =
        when (error) {
            AuthError.BAD_REQUEST -> HttpStatusCode.BadRequest to ApiErrorCode.BAD_REQUEST
            AuthError.UNAUTHORIZED -> HttpStatusCode.Unauthorized to ApiErrorCode.UNAUTHORIZED
            AuthError.INVITE_INVALID -> HttpStatusCode.Forbidden to ApiErrorCode.INVITE_INVALID
            AuthError.USERNAME_UNAVAILABLE -> HttpStatusCode.Conflict to ApiErrorCode.USERNAME_UNAVAILABLE
            AuthError.RATE_LIMITED -> HttpStatusCode.TooManyRequests to ApiErrorCode.RATE_LIMITED
        }
    respond(status, ErrorResponse(code))
}
