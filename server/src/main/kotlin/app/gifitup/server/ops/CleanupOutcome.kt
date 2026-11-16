package app.gifitup.server.ops

import java.time.Clock
import java.time.Instant

/**
 * What the last pass of one retention job did, for the once-a-minute status line (#291): when it ran, how many rows it
 * deleted, and whether it failed. Counts and times only, nothing a row held. Written by the job's thread, read by the status
 * thread, so the fields are volatile and a pair is swapped in as one value.
 */
class CleanupOutcome(
    private val clock: Clock,
) {
    private class Pass(
        val at: Instant,
        val deleted: Int,
        val failed: Boolean,
    )

    @Volatile
    private var last: Pass? = null

    fun record(
        deleted: Int,
        failed: Boolean,
    ) {
        last = Pass(clock.instant(), deleted, failed)
    }

    /** `never` before the first pass, else `ok deleted=N ago_s=T` or `failed ago_s=T`. */
    fun describe(): String {
        val pass = last ?: return "never"
        val ago = (clock.instant().epochSecond - pass.at.epochSecond).coerceAtLeast(0)
        return if (pass.failed) "failed ago_s=$ago" else "ok deleted=${pass.deleted} ago_s=$ago"
    }
}
