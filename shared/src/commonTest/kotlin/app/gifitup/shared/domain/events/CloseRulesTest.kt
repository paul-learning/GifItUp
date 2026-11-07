package app.gifitup.shared.domain.events

import kotlin.test.Test
import kotlin.test.assertEquals

/** The table of the push channel's reconnect rules (#27 plan review SEC-1, SEC-2), on its own. */
class CloseRulesTest {
    private fun step(
        code: Int?,
        relogged: Boolean = false,
    ) = CloseRules.stepFor(code, relogged)

    @Test
    fun everyCloseCodeHasItsOwnRule() {
        assertEquals(CloseRules.Step.Stop(StopReason.OUTDATED_CLIENT), step(4001))
        assertEquals(CloseRules.Step.Wait(5 * 60_000L), step(4002))
        assertEquals(CloseRules.Step.Stop(StopReason.PROTOCOL_ERROR), step(4400))
        assertEquals(CloseRules.Step.Relogin, step(4401))
        assertEquals(CloseRules.Step.Stop(StopReason.SESSION_REFUSED), step(4401, relogged = true))
        assertEquals(CloseRules.Step.Wait(60_000L), step(1008))
        assertEquals(CloseRules.Step.Wait(5_000L), step(1000))
        assertEquals(CloseRules.Step.Wait(5_000L), step(1001))
        assertEquals(CloseRules.Step.Backoff, step(1013))
        assertEquals(CloseRules.Step.Backoff, step(null))
        assertEquals(CloseRules.Step.Backoff, step(1006))
    }

    @Test
    fun theBackoffDoublesUpToAMinuteWithAtMostAQuarterJitter() {
        val none = { 0.0 }
        assertEquals(
            listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 60_000L, 60_000L),
            (1..8).map { CloseRules.backoff(it, none) },
        )
        assertEquals(1_250L, CloseRules.backoff(1, { 1.0 }))
        assertEquals(75_000L, CloseRules.backoff(40, { 1.0 }), "even after a long outage: a minute, plus the jitter")
    }
}
