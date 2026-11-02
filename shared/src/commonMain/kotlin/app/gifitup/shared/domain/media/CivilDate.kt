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
