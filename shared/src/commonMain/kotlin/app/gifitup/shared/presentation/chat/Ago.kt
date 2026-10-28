package app.gifitup.shared.presentation.chat

/** How long ago something was, in the units a chat list shows. No time zone and no calendar: just the distance. */
sealed interface Ago {
    data object JustNow : Ago

    class Minutes(
        val count: Int,
    ) : Ago

    class Hours(
        val count: Int,
    ) : Ago

    class Days(
        val count: Int,
    ) : Ago

    companion object {
        private const val MINUTE = 60L
        private const val HOUR = 3_600L
        private const val DAY = 86_400L

        /** [at] seen from [now], both seconds since the epoch; a time in the future counts as "just now". */
        fun of(
            now: Long,
            at: Long,
        ): Ago {
            val seconds = now - at
            return when {
                seconds < MINUTE -> JustNow
                seconds < HOUR -> Minutes((seconds / MINUTE).toInt())
                seconds < DAY -> Hours((seconds / HOUR).toInt())
                else -> Days((seconds / DAY).coerceAtMost(MAX_DAYS).toInt())
            }
        }

        private const val MAX_DAYS = 999L
    }
}
