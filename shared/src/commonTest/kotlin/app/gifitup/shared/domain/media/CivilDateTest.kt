package app.gifitup.shared.domain.media

import kotlin.test.Test
import kotlin.test.assertEquals

class CivilDateTest {
    @Test
    fun datesComeFromEpochDays() {
        assertEquals(CivilDate(1970, 1, 1), CivilDate.ofEpochDays(0))
        assertEquals(CivilDate(1969, 12, 31), CivilDate.ofEpochDays(-1))
        assertEquals(CivilDate(2000, 2, 29), CivilDate.ofEpochDays(11_016))
        assertEquals(CivilDate(2024, 10, 4), CivilDate.ofEpochDays(20_000))
        assertEquals(CivilDate(2100, 3, 1), CivilDate.ofEpochDays(47_541))
    }
}
