package app.gifitup.shared.domain.media

/**
 * The decoded frames of one picture, owned by whoever shows it. Up to [limitBytes] of frames are kept for the next loop;
 * a frame that does not fit is shown and let go when the next uncached one replaces it (a long animation costs time on
 * every loop, not memory). Everything it holds is released through [release] exactly once, by [close].
 */
class FrameCache<T : Any>(
    private val limitBytes: Int,
    private val release: (T) -> Unit,
) {
    private val kept = HashMap<Int, T>()
    private var used = 0
    private var uncached: T? = null

    fun get(index: Int): T? = kept[index]

    /** Takes over [frame] (of [bytes] bytes, at [index]) and returns it for showing. */
    fun put(
        index: Int,
        frame: T,
        bytes: Int,
    ): T {
        if (bytes >= 0 && used <= limitBytes - bytes && index !in kept) {
            kept[index] = frame
            used += bytes
        } else {
            uncached?.let(release)
            uncached = frame
        }
        return frame
    }

    fun close() {
        uncached?.let(release)
        uncached = null
        kept.values.forEach(release)
        kept.clear()
        used = 0
    }
}
