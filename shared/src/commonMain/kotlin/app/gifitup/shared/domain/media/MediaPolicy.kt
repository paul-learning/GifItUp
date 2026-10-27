package app.gifitup.shared.domain.media

/** A calendar date, compared field by field. */
data class CivilDate(
    val year: Int,
    val month: Int,
    val day: Int,
) : Comparable<CivilDate> {
    override fun compareTo(other: CivilDate): Int = compareValuesBy(this, other, CivilDate::year, CivilDate::month, CivilDate::day)

    companion object {
        private const val DAYS_PER_ERA = 146_097
        private const val ERA_SHIFT = 719_468

        /** The date [epochDays] days after 1970-01-01 (negative before), proleptic Gregorian. */
        @Suppress("MagicNumber") // Howard Hinnant's civil-from-days.
        fun ofEpochDays(epochDays: Long): CivilDate {
            val shifted = epochDays + ERA_SHIFT
            val era = (if (shifted >= 0) shifted else shifted - (DAYS_PER_ERA - 1)) / DAYS_PER_ERA
            val dayOfEra = shifted - era * DAYS_PER_ERA
            val yearOfEra = (dayOfEra - dayOfEra / 1460 + dayOfEra / 36524 - dayOfEra / 146096) / 365
            val dayOfYear = dayOfEra - (365 * yearOfEra + yearOfEra / 4 - yearOfEra / 100)
            val monthIndex = (5 * dayOfYear + 2) / 153
            val day = (dayOfYear - (153 * monthIndex + 2) / 5 + 1).toInt()
            val month = (if (monthIndex < 10) monthIndex + 3 else monthIndex - 9).toInt()
            val year = (yearOfEra + era * 400 + (if (month <= 2) 1 else 0)).toInt()
            return CivilDate(year, month, day)
        }
    }
}

/** Whether a received GIF is loaded when it appears, or only after the user taps it. */
enum class Autoload { AUTOMATIC, TAP_TO_LOAD }

/** The Android security patch level of this device, `YYYY-MM-DD` (`Build.VERSION.SECURITY_PATCH`), or `null` where there is none. */
fun interface DeviceSecurityPatch {
    fun level(): String?
}

/**
 * Zero-click decoding of received media is only for devices that still get codec fixes (ADR-0003, GIF spike): the OS
 * decoders (Skia, libwebp, giflib) are patched through the device maker's security updates, not through the Play
 * store. A phone whose security patch is **older than 12 months**, or whose level can't be read, gets
 * [Autoload.TAP_TO_LOAD] and the app says why. A user who taps still runs the old decoder; that is their choice.
 * A level more than a month **after** today is unreadable too (a wrong clock or a made-up level must not buy automatic
 * loading for ever). Where there is no such patch level (the web: the browser updates its own codecs) the answer is
 * [Autoload.AUTOMATIC]. [today] is the device's local calendar date.
 */
class MediaPolicy(
    private val patch: DeviceSecurityPatch?,
    private val today: () -> CivilDate,
) {
    fun autoload(): Autoload {
        val level = patch?.let { parse(it.level()) }
        val now = today()
        val implausible = level == null || level < oneYearBefore(now) || monthIndex(level) > monthIndex(now) + 1
        return if (patch != null && implausible) Autoload.TAP_TO_LOAD else Autoload.AUTOMATIC
    }

    @Suppress("MagicNumber") // Calendar numbers.
    companion object {
        private val FORMAT = Regex("^([0-9]{4})-([0-9]{2})-([0-9]{2})$")
        private const val MONTHS = 12
        private val DAYS_IN_MONTH = intArrayOf(31, 29, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)

        /** The date of a strict `YYYY-MM-DD` that exists on the calendar (leap days included), else `null`. */
        fun parse(text: String?): CivilDate? {
            val match = text?.let(FORMAT::matchEntire) ?: return null
            val (year, month, day) = match.destructured.toList().map(String::toInt)
            val valid = month in 1..MONTHS && day in 1..DAYS_IN_MONTH[month - 1] && (month != 2 || day < 29 || isLeap(year))
            return if (valid) CivilDate(year, month, day) else null
        }

        private fun monthIndex(date: CivilDate): Int = date.year * MONTHS + date.month

        /** The same day a year earlier; 29 February becomes 28 February. */
        fun oneYearBefore(date: CivilDate): CivilDate {
            val day = if (date.month == 2 && date.day == 29) 28 else date.day
            return CivilDate(date.year - 1, date.month, day)
        }

        private fun isLeap(year: Int): Boolean = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)
    }
}
