package app.gifitup.server.auth

import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Token buckets per key (ADR-0009). Keys are accounts, devices, usernames or invites, never IP addresses or headers
 * (SEC-M0-5). Three operations: [allows] looks without spending (#68 round 0, SEC-2), [spend] takes a token, [refund]
 * gives one back. [spend] is the atomic check-and-take: where a burst limit must hold under parallel requests (the
 * KeyPackage claim, ADR-0012), take the token with [spend] **before** the work and [refund] only if the work took
 * nothing; [allows] followed by [spend] lets N parallel requests all pass the check. The limiter cannot tell whether a
 * caller spent, so a caller that took several tokens (one per bucket) refunds exactly those, once each, in a `finally`,
 * and only on a path that took nothing from the pool: a stray or partial refund creates free tokens or leaves a cost
 * that lets a member empty someone else's bucket.
 *
 * Bounded: at most [maxKeys] buckets. A key's bucket is dropped only once it has refilled completely (it holds no
 * information then). If every bucket is still draining when a new key arrives, the new key is refused rather than an
 * existing one evicted, so a flood of fresh keys can't reset the bucket of a key that is being throttled (SEC-2).
 *
 * **Precondition:** buckets may only be created ([spend]) for keys the caller has authenticated or checked cheaply
 * (an existing device, a username that passed the rule, after a valid signature). Otherwise anyone could fill the map
 * and lock out every new key, and each full-map call would scan all buckets under the lock (#71 SEC-30; D2 adds a flood
 * test through the endpoints).
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
    private var lastPrune: Instant = Instant.EPOCH

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

    /**
     * Gives back one token to [key] after a [spend] whose work took nothing (never beyond the capacity). A key whose bucket
     * is gone (pruned because it was full) needs nothing.
     */
    @Synchronized
    fun refund(key: String) {
        val bucket = buckets[key] ?: return
        refill(bucket)
        bucket.tokens = minOf(capacity.toDouble(), bucket.tokens + 1.0)
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

    /**
     * Drops buckets that have refilled completely; true if that made room. At most one scan a second: with a full map of
     * draining buckets every new key would otherwise rescan all of them under the lock (#119 CR-2).
     */
    private fun pruneFull(): Boolean {
        val now = clock.instant()
        if (Duration.between(lastPrune, now) < PRUNE_INTERVAL) return buckets.size < maxKeys
        lastPrune = now
        buckets.values.forEach(::refill)
        buckets.values.removeIf { it.tokens >= capacity }
        return buckets.size < maxKeys
    }

    private companion object {
        const val DEFAULT_MAX_KEYS = 10_000
        val PRUNE_INTERVAL: Duration = Duration.ofSeconds(1)
    }
}
