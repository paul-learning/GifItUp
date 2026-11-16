package app.gifitup.server.ops

import org.slf4j.LoggerFactory
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * One INFO line a minute for the alpha's operator (#291, ARCH-M2-11): the number of open authenticated sockets and what the
 * last pass of each retention job did (`journalctl` or `docker compose logs server | grep event=status`, `docs/ops/deploy.md`).
 * Counts, times and job names only: no id, name, address or content (logging rule, `AGENTS.md` security rule 4). A failing
 * job also logs its own warning with the exception class; this line shows that it is still failing and since when.
 */
class ServerStatusLog(
    private val openSockets: () -> Int,
    private val cleanups: Map<String, CleanupOutcome>,
    private val emit: (String) -> Unit = LOG::info,
) {
    /** One line; a failure of the socket count must not stop the schedule. */
    @Suppress("TooGenericExceptionCaught") // The scheduled task must survive anything; nothing of the failure is logged.
    fun runOnce() {
        try {
            emit(line())
        } catch (ignored: Exception) {
            // The next minute tries again.
        }
    }

    internal fun line(): String =
        buildString {
            append("event=status sockets=").append(openSockets())
            cleanups.forEach { (name, outcome) ->
                append(' ')
                    .append(name)
                    .append("=\"")
                    .append(outcome.describe())
                    .append('"')
            }
        }

    private companion object {
        val LOG: org.slf4j.Logger = LoggerFactory.getLogger(ServerStatusLog::class.java)
    }

    /** Runs once a minute on a daemon thread; shut the returned executor down on shutdown. */
    fun start(): ScheduledExecutorService =
        Executors
            .newSingleThreadScheduledExecutor { Thread(it, "server-status").apply { isDaemon = true } }
            .also { it.scheduleAtFixedRate(::runOnce, 1, 1, TimeUnit.MINUTES) }
}
