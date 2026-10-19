package app.gifitup.server.db

import org.slf4j.LoggerFactory
import java.time.Clock
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Deletes expired KeyPackages hourly (ADR-0012), in batches of [batchSize] rows, one transaction each, with a pause
 * between batches, so a large backlog never holds many row locks at once. Logs only how many rows went.
 */
class KeyPackageCleanup(
    private val db: TransactionRunner,
    private val clock: Clock,
    private val repository: KeyPackageRepository = KeyPackageRepository(),
    private val batchSize: Int = BATCH_SIZE,
    private val pause: () -> Unit = { Thread.sleep(PAUSE_MILLIS) },
) {
    private val log = LoggerFactory.getLogger(KeyPackageCleanup::class.java)

    /** One pass; returns how many rows were deleted. A failure is logged by class and retried on the next pass. */
    fun runOnce(): Int {
        var total = 0
        try {
            do {
                val deleted = db.transaction { c -> repository.deleteExpired(c, clock.instant(), batchSize) }
                total += deleted
                if (deleted == batchSize) pause()
            } while (deleted == batchSize)
        } catch (e: java.sql.SQLException) {
            log.warn("event=key_package_cleanup_failed exception={}", e::class.java.name)
        }
        if (total > 0) log.info("event=key_package_cleanup deleted={}", total)
        return total
    }

    /** Runs [runOnce] now and then every hour on a daemon thread; shut the returned executor down on shutdown. */
    fun start(): ScheduledExecutorService =
        Executors
            .newSingleThreadScheduledExecutor { Thread(it, "key-package-cleanup").apply { isDaemon = true } }
            .also { it.scheduleAtFixedRate(::runOnce, 0, 1, TimeUnit.HOURS) }

    private companion object {
        const val BATCH_SIZE = 1000
        const val PAUSE_MILLIS = 100L
    }
}
