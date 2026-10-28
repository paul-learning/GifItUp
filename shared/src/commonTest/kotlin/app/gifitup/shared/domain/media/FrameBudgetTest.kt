package app.gifitup.shared.domain.media

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FrameBudgetTest {
    @Test
    fun framesAreKeptUpToTheLimitAndNotBeyond() {
        val budget = FrameBudget(100)
        assertTrue(budget.keep(60))
        assertFalse(budget.keep(41))
        assertTrue(budget.keep(40))
        assertFalse(budget.keep(1))
    }

    @Test
    fun aFrameLargerThanTheWholeBudgetIsNeverKeptAndNothingOverflows() {
        val budget = FrameBudget(100)
        assertFalse(budget.keep(101))
        assertFalse(budget.keep(Int.MAX_VALUE))
        assertTrue(budget.keep(100))
    }
}
