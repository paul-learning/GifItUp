package app.gifitup.shared.domain.media

/** How long a frame stays: browsers show a GIF delay of 10 ms or less as 100 ms, and so does this app. */
object GifDelay {
    private const val SHORTEST_MS = 10
    private const val DEFAULT_MS = 100L

    fun millis(declared: Int): Long = if (declared <= SHORTEST_MS) DEFAULT_MS else declared.toLong()
}
