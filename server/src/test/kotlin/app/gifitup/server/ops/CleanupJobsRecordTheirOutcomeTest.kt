package app.gifitup.server.ops

import app.gifitup.server.auth.InviteCleanup
import app.gifitup.server.db.DeliveryCleanup
import app.gifitup.server.db.KeyPackageCleanup
import app.gifitup.server.db.TransactionRunner
import java.sql.Connection
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Each retention job tells the status line how its last pass went, on the failure path too (#291). No database needed. */
class CleanupJobsRecordTheirOutcomeTest {
    private val clock =
        object : Clock() {
            override fun getZone(): ZoneId = ZoneOffset.UTC

            override fun withZone(zone: ZoneId?): Clock = this

            override fun instant(): Instant = Instant.parse("2026-11-17T10:00:00Z")
        }

    private val failing =
        object : TransactionRunner {
            override fun <T> transaction(block: (Connection) -> T): T = error("db down")
        }

    private class Counting(
        private val rows: Int,
    ) : TransactionRunner {
        @Suppress("UNCHECKED_CAST") // Every transaction of these two jobs returns a row count.
        override fun <T> transaction(block: (Connection) -> T): T = rows as T
    }

    @Test
    fun theInviteJob() {
        val failed = InviteCleanup(failing, clock)
        failed.runOnce()
        assertEquals("failed ago_s=0", failed.outcome.describe())
        val ok = InviteCleanup(Counting(5), clock)
        ok.runOnce()
        assertEquals("ok deleted=5 ago_s=0", ok.outcome.describe())
    }

    @Test
    fun theKeyPackageJob() {
        val failed = KeyPackageCleanup(failing, clock)
        failed.runOnce()
        assertEquals("failed ago_s=0", failed.outcome.describe())
        val ok = KeyPackageCleanup(Counting(0), clock)
        ok.runOnce()
        assertEquals("ok deleted=0 ago_s=0", ok.outcome.describe())
    }

    @Test
    fun theDeliveryJobFailurePath() {
        val failed = DeliveryCleanup(failing, clock)
        assertEquals("never", failed.outcome.describe())
        failed.runOnce()
        assertTrue(failed.outcome.describe().startsWith("failed"))
    }
}
