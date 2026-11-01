package app.gifitup.server.config

import app.gifitup.protocol.ApiErrorCode
import app.gifitup.protocol.ErrorResponse
import app.gifitup.protocol.GifProviderResponse
import app.gifitup.server.auth.KeyedRateLimiter
import app.gifitup.server.auth.session
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import java.time.Clock
import java.time.Duration

internal const val GIF_PROVIDER_PATH = "/config/gif-provider"

/**
 * What `GET /config/gif-provider` answers with: the [key] (or none), and a per-device limiter. The limiter is defence in
 * depth over the per-account budget (ADR-0009): clients fetch with login traffic and about every 6 hours, so 10 in a
 * burst and one more every 6 minutes is generous.
 */
class GifProviderEndpoint(
    internal val key: GifProviderKey?,
    internal val limiter: KeyedRateLimiter,
) {
    companion object {
        private const val BURST = 10
        private const val REFILL_MINUTES = 6L

        fun of(
            key: GifProviderKey?,
            clock: Clock,
        ): GifProviderEndpoint = GifProviderEndpoint(key, KeyedRateLimiter(clock, BURST, Duration.ofMinutes(REFILL_MINUTES)))

        /** No key configured: the endpoint answers 404, like a server from before it. */
        fun unconfigured(clock: Clock = Clock.systemUTC()): GifProviderEndpoint = of(null, clock)
    }
}

/**
 * `GET /config/gif-provider` (ADR-0003, #40). Not public, so the session interceptor has already checked the caller. The
 * handler takes nothing from the request: the session only authorises, and every device gets the same bytes. The limiter
 * is keyed by the session's device and spends before anything else, and nothing is given back.
 */
internal fun Route.gifProviderRoutes(endpoint: GifProviderEndpoint) {
    get(GIF_PROVIDER_PATH) {
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        val key = endpoint.key
        when {
            key == null -> {
                call.respond(HttpStatusCode.NotFound, ErrorResponse(ApiErrorCode.NOT_FOUND))
            }

            !endpoint.limiter.spend(
                call.session.record.deviceId
                    .toString(),
            ) -> {
                call.respond(HttpStatusCode.TooManyRequests, ErrorResponse(ApiErrorCode.RATE_LIMITED))
            }

            else -> {
                call.respond(GifProviderResponse(key.reveal()))
            }
        }
    }
}
