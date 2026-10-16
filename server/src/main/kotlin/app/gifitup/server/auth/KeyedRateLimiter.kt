package app.gifitup.server.auth

import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Token buckets per key (ADR-0009). Keys are accounts, devices, usernames or invites, never IP addresses or headers
 * (SEC-M0-5). Two operations, so a caller can spend only on success (#68 round 0, SEC-2): [allows] looks without
 * spending, [spend] takes a token.
 *
 * Bounded: at most [maxKeys] buckets. A key's bucket is dropped only once it has refilled completely (it holds no
 * information then). If every bucket is still draining when a new key arrives, the new key is refused rather than an
 * existing one evicted, so a flood of fresh keys can't reset the bucket of a key that is being throttled (SEC-2).
 */
class KeyedRateLimiter(
    private val clock: Clock,
    private val capacity: Int,
    private val refillEvery: Duration,
    private val maxKeys: Int = DEFAULT_MAX_KEYS,
) {
    init {
        require(capacity > 0 && maxKeys > 0 && !refillEvery.isNegative && !refillEvery.isZero) { "invalid limits" }
    }

    private class Bucket(
        var tokens: Double,
        var updated: Instant,
    )

    private val buckets = HashMap<String, Bucket>()

    /** True if [key] has a token left. Doesn't spend it. A key without a bucket is refused only when the map is full. */
    @Synchronized
    fun allows(key: String): Boolean {
        val bucket = buckets[key] ?: return buckets.size < maxKeys || pruneFull()
        refill(bucket)
        return bucket.tokens >= 1.0
    }

    /** Takes one token from [key]'s bucket; false (and nothing spent) if none is left or no bucket can be created. */
    @Synchronized
    fun spend(key: String): Boolean {
        val bucket = buckets[key] ?: newBucket(key) ?: return false
        refill(bucket)
        val ok = bucket.tokens >= 1.0
        if (ok) bucket.tokens -= 1.0
        return ok
    }

    /** A full bucket for [key], or null if the map is full of buckets that are still draining. */
    private fun newBucket(key: String): Bucket? {
        if (buckets.size >= maxKeys && !pruneFull()) return null
        return Bucket(capacity.toDouble(), clock.instant()).also { buckets[key] = it }
    }

    private fun refill(bucket: Bucket) {
        val now = clock.instant()
        val elapsed = Duration.between(bucket.updated, now)
        if (elapsed.isNegative) return
        bucket.tokens = minOf(capacity.toDouble(), bucket.tokens + elapsed.toNanos().toDouble() / refillEvery.toNanos())
        bucket.updated = now
    }

    /** Drops buckets that have refilled completely; true if that made room. */
    private fun pruneFull(): Boolean {
        buckets.values.forEach(::refill)
        buckets.values.removeIf { it.tokens >= capacity }
        return buckets.size < maxKeys
    }

    private companion object {
        const val DEFAULT_MAX_KEYS = 10_000
    }
}
