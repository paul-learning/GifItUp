package app.gifitup.server.delivery

import app.gifitup.server.auth.KeyedRateLimiter
import java.time.Clock
import java.time.Duration

/**
 * The Delivery Service rate limits (ADR-0015), keyed by device id and spent only for an authenticated, existing device
 * (the session interceptor has checked it), so the maps cannot be filled by strangers.
 * - [posts]: every post, burst 60, one more per second.
 * - [commits]: a commit also spends this, burst 5, one more per minute.
 * - [reads]: fetch, status, ack, leave and the welcome calls, burst 120, two more per second.
 * - [creates]: new groups, burst 3, one more per hour.
 */
class DeliveryLimits(
    clock: Clock,
    val posts: KeyedRateLimiter = KeyedRateLimiter(clock, POST_BURST, Duration.ofSeconds(1), MAX_DEVICES),
    val commits: KeyedRateLimiter = KeyedRateLimiter(clock, COMMIT_BURST, Duration.ofMinutes(1), MAX_DEVICES),
    val reads: KeyedRateLimiter = KeyedRateLimiter(clock, READ_BURST, Duration.ofMillis(READ_REFILL_MS), MAX_DEVICES),
    val creates: KeyedRateLimiter = KeyedRateLimiter(clock, CREATE_BURST, Duration.ofHours(1), MAX_DEVICES),
) {
    private companion object {
        const val POST_BURST = 60
        const val COMMIT_BURST = 5
        const val READ_BURST = 120
        const val READ_REFILL_MS = 500L
        const val CREATE_BURST = 3

        /** Above the number of device rows of a friends-sized server; a full map still refuses only new keys. */
        const val MAX_DEVICES = 10_000
    }
}
