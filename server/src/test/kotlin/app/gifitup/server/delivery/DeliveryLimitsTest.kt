package app.gifitup.server.delivery

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The defaults of the new-group limit (owner decision 2026-11-05, #27): 10 in a burst, then one more per 10 minutes. */
class DeliveryLimitsTest {
    private class MutableClock(
        private var now: Instant,
    ) : Clock() {
        override fun getZone() = ZoneOffset.UTC

        override fun withZone(zone: java.time.ZoneId?): Clock = this

        override fun instant(): Instant = now

        fun advance(by: Duration) {
            now = now.plus(by)
        }
    }

    @Test
    fun `a device may create ten groups at once and then one more every ten minutes`() {
        val clock = MutableClock(Instant.parse("2026-11-05T12:00:00Z"))
        val creates = DeliveryLimits(clock).creates
        repeat(10) { assertTrue(creates.spend("device"), "creation ${it + 1} of the burst") }
        assertFalse(creates.spend("device"), "the eleventh is refused")
        clock.advance(Duration.ofMinutes(9))
        assertFalse(creates.spend("device"), "nine minutes is not enough")
        clock.advance(Duration.ofMinutes(1))
        assertTrue(creates.spend("device"), "one more after ten minutes")
        assertFalse(creates.spend("device"), "and only one")
    }

    @Test
    fun `the limit is per device`() {
        val clock = MutableClock(Instant.parse("2026-11-05T12:00:00Z"))
        val creates = DeliveryLimits(clock).creates
        repeat(10) { creates.spend("one") }
        assertFalse(creates.spend("one"))
        assertTrue(creates.spend("other"), "another device has its own budget")
    }
}
