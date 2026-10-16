package app.gifitup.server.auth

import app.gifitup.server.db.InviteRepository
import app.gifitup.server.db.TransactionRunner
import org.slf4j.LoggerFactory
import java.time.Clock
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Invite retention (#13 SEC-45, ADR-0010): deletes finished invites hourly ([InviteRepository.deleteFinished]). Logs
 * only how many rows went.
 */
class InviteCleanup(
    private val db: TransactionRunner,
    private val clock: Clock,
    private val invites: InviteRepository = InviteRepository(),
) {
    private val log = LoggerFactory.getLogger(InviteCleanup::class.java)

    /** One pass; returns how many invites were deleted. Failures are logged by class and retried on the next pass. */
    fun runOnce(): Int =
        runCatching { db.transaction { c -> invites.deleteFinished(c, clock.instant()) } }
            .onSuccess { if (it > 0) log.info("event=invite_cleanup deleted={}", it) }
            .onFailure { log.warn("event=invite_cleanup_failed exception={}", it::class.java.name) }
            .getOrDefault(0)

    /** Runs [runOnce] now and then every hour on a daemon thread; close the returned executor on shutdown. */
    fun start(): ScheduledExecutorService =
        Executors
            .newSingleThreadScheduledExecutor { Thread(it, "invite-cleanup").apply { isDaemon = true } }
            .also { it.scheduleAtFixedRate(::runOnce, 0, 1, TimeUnit.HOURS) }
}
