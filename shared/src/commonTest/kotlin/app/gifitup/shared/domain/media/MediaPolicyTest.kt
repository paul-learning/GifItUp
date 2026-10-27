package app.gifitup.shared.domain.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MediaPolicyTest {
    private fun policy(
        patch: String?,
        today: CivilDate = CivilDate(2026, 10, 27),
    ) = MediaPolicy({ patch }, { today }).autoload()

    @Test
    fun aRecentPatchLoadsAutomaticallyAndAnOldOneNeedsATap() {
        assertEquals(Autoload.AUTOMATIC, policy("2026-10-05"))
        assertEquals(Autoload.AUTOMATIC, policy("2025-11-01"))
        assertEquals(Autoload.TAP_TO_LOAD, policy("2025-10-26"))
        assertEquals(Autoload.TAP_TO_LOAD, policy("2023-01-05"))
        assertEquals(Autoload.TAP_TO_LOAD, policy("2019-09-01"))
    }

    @Test
    fun exactlyTwelveMonthsIsStillFine() {
        assertEquals(Autoload.AUTOMATIC, policy("2025-10-27"))
        assertEquals(Autoload.TAP_TO_LOAD, policy("2025-10-26"))
        // 29 February: a year before it is 28 February.
        assertEquals(Autoload.AUTOMATIC, policy("2027-02-28", today = CivilDate(2028, 2, 29)))
        assertEquals(Autoload.TAP_TO_LOAD, policy("2027-02-27", today = CivilDate(2028, 2, 29)))
    }

    @Test
    fun aPatchFromTheNearFutureIsFineButAFarOneIsNotTrusted() {
        assertEquals(Autoload.AUTOMATIC, policy("2026-11-30"), "a clock a few weeks off")
        assertEquals(Autoload.TAP_TO_LOAD, policy("2026-12-01"))
        assertEquals(Autoload.TAP_TO_LOAD, policy("2027-05-01"))
        assertEquals(Autoload.TAP_TO_LOAD, policy("9999-12-31"))
        assertEquals(Autoload.AUTOMATIC, policy("2027-01-31", today = CivilDate(2026, 12, 31)))
        assertEquals(Autoload.TAP_TO_LOAD, policy("2027-02-01", today = CivilDate(2026, 12, 31)))
    }

    @Test
    fun anUnreadablePatchLevelFailsClosed() {
        for (bad in listOf(
            null,
            "",
            "2026-1-5",
            "26-10-05",
            "2026/10/05",
            "2026-10-05\n",
            " 2026-10-05",
            "2026-13-01",
            "2026-00-10",
            "2026-10-32",
            "2026-02-30",
            "2027-02-29",
            "text",
        )) {
            assertEquals(Autoload.TAP_TO_LOAD, policy(bad), bad.toString())
        }
        assertNull(MediaPolicy.parse("2100-02-29"))
        assertEquals(CivilDate(2028, 2, 29), MediaPolicy.parse("2028-02-29"))
    }

    @Test
    fun withoutAPatchLevelTheDeviceUpdatesItsOwnCodecs() {
        assertEquals(Autoload.AUTOMATIC, MediaPolicy(null) { CivilDate(2026, 10, 27) }.autoload())
    }

    @Test
    fun datesComeFromEpochDays() {
        assertEquals(CivilDate(1970, 1, 1), CivilDate.ofEpochDays(0))
        assertEquals(CivilDate(1969, 12, 31), CivilDate.ofEpochDays(-1))
        assertEquals(CivilDate(2000, 2, 29), CivilDate.ofEpochDays(11_016))
        assertEquals(CivilDate(2024, 10, 4), CivilDate.ofEpochDays(20_000))
        assertEquals(CivilDate(2100, 3, 1), CivilDate.ofEpochDays(47_541))
    }
}
