package app.gifitup.ui

import java.text.DateFormat
import java.util.Date

internal actual fun formatClockTime(epochSeconds: Long): String =
    DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(epochSeconds * MILLIS_PER_SECOND))

private const val MILLIS_PER_SECOND = 1_000L
