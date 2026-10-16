package app.gifitup.server.http

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** Whether the server can serve requests; `/health` answers 503 when it can't (ADR-0008). */
fun interface ReadinessProbe {
    suspend fun isReady(): Boolean
}

/**
 * Caches a blocking [check] (the database ping) for [ttl], so a flood of public `/health` requests costs at most one
 * check per [ttl] (#12 round 0). Concurrent callers wait for the one check in flight instead of starting their own.
 */
class CachedReadiness(
    private val clock: Clock,
    private val ttl: Duration = Duration.ofSeconds(DEFAULT_TTL_SECONDS),
    private val check: () -> Boolean,
) : ReadinessProbe {
    private val mutex = Mutex()

    @Volatile
    private var last: Pair<Instant, Boolean>? = null

    override suspend fun isReady(): Boolean {
        fresh()?.let { return it }
        return mutex.withLock {
            fresh() ?: withContext(Dispatchers.IO) { check() }.also { last = clock.instant() to it }
        }
    }

    private fun fresh(): Boolean? = last?.takeIf { (at, _) -> Duration.between(at, clock.instant()) < ttl }?.second

    private companion object {
        const val DEFAULT_TTL_SECONDS = 5L
    }
}
