package app.gifitup.ui

internal actual fun formatClockTime(epochSeconds: Long): String = clockTime(epochSeconds.toDouble() * MILLIS_PER_SECOND)

@JsFun("(millis) => new Date(millis).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })")
private external fun clockTime(millis: Double): String

private const val MILLIS_PER_SECOND = 1_000.0
