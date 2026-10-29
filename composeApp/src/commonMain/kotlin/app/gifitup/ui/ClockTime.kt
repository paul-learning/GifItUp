package app.gifitup.ui

/** The time of day of [epochSeconds] in the user's time zone and format ("21:04", or "9:04 PM" where that is the habit). */
internal expect fun formatClockTime(epochSeconds: Long): String
