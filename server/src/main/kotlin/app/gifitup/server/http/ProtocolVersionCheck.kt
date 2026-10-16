package app.gifitup.server.http

import app.gifitup.protocol.ApiErrorCode
import app.gifitup.protocol.ClientOutdatedResponse
import app.gifitup.protocol.ErrorResponse
import app.gifitup.protocol.MIN_SUPPORTED_PROTOCOL_VERSION
import app.gifitup.protocol.PROTOCOL_VERSION
import app.gifitup.protocol.PROTOCOL_VERSION_HEADER
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.request.path
import io.ktor.server.response.respond

/** The only route a client may call without the protocol header (ADR-0006 A4). */
internal const val HEALTH_PATH = "/health"

private val VERSION_PATTERN = Regex("^[1-9][0-9]{0,3}$")

/** Outcome of checking the `GifItUp-Protocol` header. */
internal sealed interface VersionCheck {
    data object Supported : VersionCheck

    data object ClientOutdated : VersionCheck

    data object ServerOutdated : VersionCheck
}

/**
 * Checks the raw header values (ADR-0006 A3): exactly one occurrence, matching `^[1-9][0-9]{0,3}$`. Missing,
 * repeated, malformed or too old → [VersionCheck.ClientOutdated]; newer than [current] → [VersionCheck.ServerOutdated].
 */
internal fun checkProtocolVersion(
    headerValues: List<String>?,
    min: Int = MIN_SUPPORTED_PROTOCOL_VERSION,
    current: Int = PROTOCOL_VERSION,
): VersionCheck {
    val version = headerValues?.singleOrNull()?.takeIf { VERSION_PATTERN.matches(it) }?.toInt()
    return when {
        version == null || version < min -> VersionCheck.ClientOutdated
        version > current -> VersionCheck.ServerOutdated
        else -> VersionCheck.Supported
    }
}

/**
 * Rejects every request whose protocol version this server doesn't serve, except requests to `/health` (any method:
 * only `GET` has a route there, so the others get a closed 405). Intercepts the whole application, so a new route is
 * covered without opting in. A rejected call is finished here, so routing, route-scoped plugins (auth, rate limits) and
 * handlers never run for it (#68 SEC-16). The raw header value is never logged or echoed.
 */
internal fun Application.installProtocolVersionCheck() {
    intercept(ApplicationCallPipeline.Plugins) {
        if (call.request.path() == HEALTH_PATH) return@intercept
        when (checkProtocolVersion(call.request.headers.getAll(PROTOCOL_VERSION_HEADER))) {
            VersionCheck.Supported -> {
                return@intercept
            }

            VersionCheck.ClientOutdated -> {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ClientOutdatedResponse(ApiErrorCode.CLIENT_OUTDATED, MIN_SUPPORTED_PROTOCOL_VERSION),
                )
            }

            VersionCheck.ServerOutdated -> {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(ApiErrorCode.SERVER_OUTDATED))
            }
        }
        finish()
    }
}
