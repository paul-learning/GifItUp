package app.gifitup.server.ops

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ServerStatusLogTest {
    private class MutableClock(
        var now: Instant,
    ) : Clock() {
        override fun getZone() = ZoneOffset.UTC

        override fun withZone(zone: java.time.ZoneId?) = this

        override fun instant(): Instant = now
    }

    private val clock = MutableClock(Instant.parse("2026-11-17T10:00:00Z"))

    @Test
    fun aJobThatNeverRanSaysSo() {
        assertEquals("never", CleanupOutcome(clock).describe())
    }

    @Test
    fun theOutcomeSaysCountAndAgeOrThatItFailed() {
        val outcome = CleanupOutcome(clock)
        outcome.record(deleted = 7, failed = false)
        clock.now = clock.now.plusSeconds(90)
        assertEquals("ok deleted=7 ago_s=90", outcome.describe())
        outcome.record(deleted = 0, failed = true)
        clock.now = clock.now.plusSeconds(5)
        assertEquals("failed ago_s=5", outcome.describe())
    }

    @Test
    fun theStatusLineHasOnlyCountsTimesAndJobNames() {
        val ok = CleanupOutcome(clock).also { it.record(3, failed = false) }
        val never = CleanupOutcome(clock)
        val lines = mutableListOf<String>()
        ServerStatusLog({ 12 }, linkedMapOf("invite_cleanup" to ok, "delivery_cleanup" to never), lines::add).runOnce()
        assertEquals(
            listOf("event=status sockets=12 invite_cleanup=\"ok deleted=3 ago_s=0\" delivery_cleanup=\"never\""),
            lines,
        )
        assertTrue(Regex("^event=status sockets=[0-9]+( [a-z_]+=\"[a-z0-9_= ]+\")*$").matches(lines.single()))
    }

    @Test
    fun aFailingSocketCountDoesNotThrowAndTheNextRunStillEmits() {
        var fail = true
        val lines = mutableListOf<String>()
        val log = ServerStatusLog({ if (fail) error("boom") else 1 }, emptyMap(), lines::add)
        log.runOnce()
        fail = false
        log.runOnce()
        assertEquals(listOf("event=status sockets=1"), lines)
    }
}
