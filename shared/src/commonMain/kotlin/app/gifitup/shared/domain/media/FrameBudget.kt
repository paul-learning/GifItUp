package app.gifitup.shared.domain.media

/**
 * How many decoded frames of one picture are kept for the next loop: up to [limitBytes] in all. A frame that does not
 * fit is shown and let go, and decoded again on the next loop (a long animation costs time, not memory).
 */
class FrameBudget(
    private val limitBytes: Int,
) {
    private var used = 0

    /** Whether a frame of [bytes] is kept; then it counts against the budget. */
    fun keep(bytes: Int): Boolean {
        val fits = bytes >= 0 && used <= limitBytes - bytes
        if (fits) used += bytes
        return fits
    }
}
