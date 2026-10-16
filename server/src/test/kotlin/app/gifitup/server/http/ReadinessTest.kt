package app.gifitup.server.http

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReadinessTest {
    private var now = Instant.parse("2026-10-16T12:00:00Z")
    private val clock =
        object : Clock() {
            override fun getZone() = ZoneOffset.UTC

            override fun withZone(zone: java.time.ZoneId?) = this

            override fun instant() = now
        }

    @Test
    fun resultIsCachedForTheTtl() =
        runBlocking {
            val checks = AtomicInteger()
            var healthy = true
            val readiness = CachedReadiness(clock, Duration.ofSeconds(5)) { checks.incrementAndGet().let { healthy } }

            assertTrue(readiness.isReady())
            healthy = false
            now = now.plusSeconds(4)
            assertTrue(readiness.isReady(), "still the cached result")
            assertEquals(1, checks.get())

            now = now.plusSeconds(1)
            assertFalse(readiness.isReady())
            assertEquals(2, checks.get())
        }

    @Test
    fun concurrentCallersShareOneCheck() =
        runBlocking {
            val checks = AtomicInteger()
            val readiness =
                CachedReadiness(clock) {
                    checks.incrementAndGet()
                    Thread.sleep(100)
                    true
                }

            val results = (1..50).map { async { readiness.isReady() } }.awaitAll()

            assertTrue(results.all { it })
            assertEquals(1, checks.get())
        }
}
