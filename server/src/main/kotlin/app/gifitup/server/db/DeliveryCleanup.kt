package app.gifitup.server.db

import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Hourly retention for the Delivery Service (ADR-0015): messages every active member has acknowledged (a member silent
 * for [activeWindow] stops holding them back), messages and welcomes older than [ttl], groups without members, the
 * add log (kept two days, the add limit's window is one) and the groups' retained-byte counters. Each step is its
 * own transaction; logs only how many message, welcome and group rows went.
 */
class DeliveryCleanup(
    private val db: TransactionRunner,
    private val clock: Clock,
    private val repository: DeliveryRepository = DeliveryRepository(),
    private val ttl: Duration = Duration.ofDays(TTL_DAYS),
    private val activeWindow: Duration = Duration.ofDays(ACTIVE_DAYS),
) {
    private val log = LoggerFactory.getLogger(DeliveryCleanup::class.java)

    /**
     * One pass; returns how many rows were deleted. A failure is logged by class and retried on the next pass: an
     * exception escaping a task given to `scheduleAtFixedRate` would silently cancel every later run.
     */
    @Suppress("TooGenericExceptionCaught") // The scheduled task must survive anything; only the class name is logged.
    fun runOnce(): Int {
        var total = 0
        try {
            val now = clock.instant()
            total += db.transaction { c -> repository.deleteAcked(c, now.minus(activeWindow)) }
            total += db.transaction { c -> repository.deleteMessagesBefore(c, now.minus(ttl)) }
            total += db.transaction { c -> repository.deleteWelcomesBefore(c, now.minus(ttl)) }
            total += db.transaction { c -> repository.deleteEmptyGroups(c) }
            db.transaction { c -> repository.deleteAddsBefore(c, now.minus(ADDS_KEPT)) }
            db.transaction { c -> repository.recountRetainedBytes(c) }
        } catch (e: Exception) {
            log.warn("event=delivery_cleanup_failed exception={}", e::class.java.name)
        }
        if (total > 0) log.info("event=delivery_cleanup deleted={}", total)
        return total
    }

    /** Runs [runOnce] now and then every hour on a daemon thread; shut the returned executor down on shutdown. */
    fun start(): ScheduledExecutorService =
        Executors
            .newSingleThreadScheduledExecutor { Thread(it, "delivery-cleanup").apply { isDaemon = true } }
            .also { it.scheduleAtFixedRate(::runOnce, 0, 1, TimeUnit.HOURS) }

    private companion object {
        const val TTL_DAYS = 30L
        val ADDS_KEPT: Duration = Duration.ofDays(2)
        const val ACTIVE_DAYS = 7L
    }
}
