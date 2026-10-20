package app.gifitup.server.auth

import app.gifitup.keypackage.hex
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UsernamePolicyTest {
    /** The same file the crate's `username_rule` test reads (ADR-0007): both rules must agree on every case. */
    @Test
    fun matchesTheCratesRuleOnTheSharedCases() {
        val lines =
            checkNotNull(javaClass.getResource("/username-rule-cases.txt"))
                .readText()
                .lines()
                .filter { it.isNotEmpty() && !it.startsWith("#") }
        lines.forEach { line ->
            val name = hex(line.substring(1).trim()).decodeToString()
            assertEquals(line.startsWith("+"), UsernamePolicy.isValid(name), name)
        }
        assertTrue(lines.size >= 20, "the shared file lost cases")
    }

    @Test
    fun reservedNamesAreValidButReserved() {
        listOf("admin", "root", "gifitup", "system", "support", "null").forEach { name ->
            assertTrue(UsernamePolicy.isValid(name), name)
            assertTrue(UsernamePolicy.isReserved(name), name)
        }
        assertFalse(UsernamePolicy.isReserved("alice"))
    }
}

class KeyedRateLimiterTest {
    private val clock = TestClock()

    @Test
    fun spendsUpToCapacityThenRefills() {
        val limiter = KeyedRateLimiter(clock, capacity = 3, refillEvery = Duration.ofSeconds(10))
        repeat(3) { assertTrue(limiter.spend("a")) }
        assertFalse(limiter.spend("a"))
        assertFalse(limiter.allows("a"))
        assertTrue(limiter.spend("b"), "keys are independent")
        clock.advance(Duration.ofSeconds(10))
        assertTrue(limiter.allows("a"))
        assertTrue(limiter.spend("a"))
        assertFalse(limiter.spend("a"))
    }

    @Test
    fun allowsDoesNotSpend() {
        val limiter = KeyedRateLimiter(clock, capacity = 1, refillEvery = Duration.ofMinutes(1))
        repeat(10) { assertTrue(limiter.allows("a")) }
        assertTrue(limiter.spend("a"))
        assertFalse(limiter.allows("a"))
    }

    /** SEC-2: a flood of fresh keys must not evict, and so reset, a key that is being throttled. */
    @Test
    fun aFloodOfNewKeysDoesNotResetAThrottledKey() {
        val limiter = KeyedRateLimiter(clock, capacity = 2, refillEvery = Duration.ofMinutes(1), maxKeys = 3)
        repeat(2) { limiter.spend("victim") }
        assertFalse(limiter.allows("victim"))
        val accepted = (1..10_000).count { limiter.spend("random-$it") }

        assertTrue(accepted <= 2, "only free slots are used, got $accepted")
        assertFalse(limiter.allows("victim"), "still throttled")
        assertFalse(limiter.spend("victim"))
    }

    @Test
    fun refilledBucketsMakeRoomAgain() {
        val limiter = KeyedRateLimiter(clock, capacity = 1, refillEvery = Duration.ofSeconds(1), maxKeys = 2)
        assertTrue(limiter.spend("a"))
        assertTrue(limiter.spend("b"))
        assertFalse(limiter.spend("c"), "full, and both buckets are still draining")
        clock.advance(Duration.ofSeconds(1))
        assertTrue(limiter.spend("c"), "a and b refilled completely, so their buckets were dropped")
    }

    /** ADR-0012: the burst of a claim limit holds under parallel requests, because the token is taken before the work. */
    @Test
    fun parallelSpendsNeverTakeMoreThanTheBurst() {
        val limiter = KeyedRateLimiter(clock, capacity = 2, refillEvery = Duration.ofDays(1))
        val granted =
            java.util.concurrent.atomic
                .AtomicInteger()
        val start = java.util.concurrent.CountDownLatch(1)
        val threads =
            List(100) {
                Thread {
                    start.await()
                    if (limiter.spend("claimer")) granted.incrementAndGet()
                }.apply { start() }
            }
        start.countDown()
        threads.forEach { it.join() }
        assertEquals(2, granted.get())
    }

    @Test
    fun aRefundGivesBackOneTokenButNeverBeyondTheCapacity() {
        val limiter = KeyedRateLimiter(clock, capacity = 2, refillEvery = Duration.ofDays(1))
        assertTrue(limiter.spend("a"))
        assertTrue(limiter.spend("a"))
        assertFalse(limiter.spend("a"))
        limiter.refund("a")
        assertTrue(limiter.spend("a"), "the refunded token is usable")
        assertFalse(limiter.spend("a"))
        limiter.refund("a")
        limiter.refund("a")
        limiter.refund("a")
        assertTrue(limiter.spend("a"))
        assertTrue(limiter.spend("a"))
        assertFalse(limiter.spend("a"), "capacity is 2, however many refunds")
    }

    @Test
    fun refundingAKeyWithoutABucketDoesNothing() {
        val limiter = KeyedRateLimiter(clock, capacity = 1, refillEvery = Duration.ofDays(1), maxKeys = 1)
        limiter.refund("unknown")
        assertTrue(limiter.spend("a"), "no bucket was created for the unknown key")
    }

    /** Spends and refunds racing each other can never leave more tokens than the capacity. */
    @Test
    fun racingSpendsAndRefundsNeverExceedTheCapacity() {
        val limiter = KeyedRateLimiter(clock, capacity = 3, refillEvery = Duration.ofDays(1))
        val start = java.util.concurrent.CountDownLatch(1)
        val threads =
            List(40) {
                Thread {
                    start.await()
                    if (limiter.spend("a")) limiter.refund("a")
                    limiter.refund("a")
                }.apply { start() }
            }
        start.countDown()
        threads.forEach { it.join() }
        assertEquals(3, (1..10).count { limiter.spend("a") }, "never more than the capacity, however they interleaved")
    }

    @Test
    fun aFullMapScansForRoomAtMostOncePerSecond() {
        val clock = TestClock()
        val limiter = KeyedRateLimiter(clock, 1, Duration.ofMillis(500), maxKeys = 2)
        assertTrue(limiter.spend("a"))
        assertTrue(limiter.spend("b"))
        assertFalse(limiter.spend("c"), "full of draining buckets")
        clock.advance(Duration.ofMillis(600)) // both refilled, but the last scan was under a second ago
        assertFalse(limiter.spend("c"), "no new scan yet")
        clock.advance(Duration.ofMillis(400))
        assertTrue(limiter.spend("c"), "a second after the last scan the full buckets are dropped")
    }
}
