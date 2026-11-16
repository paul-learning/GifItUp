package app.gifitup.shared.presentation.settings

import kotlin.test.Test
import kotlin.test.assertEquals

class WindowProtectionCounterTest {
    private val flags = mutableListOf<Boolean>()
    private val counter = WindowProtectionCounter { flags += it }

    @Test
    fun theStartUpHoldProtectsAtOnceAndIsReleasedOnlyOnce() {
        counter.holdUntilSettled()
        counter.holdUntilSettled()
        assertEquals(listOf(true), flags)
        counter.acquire() // the app-wide setting takes over
        counter.settled()
        counter.settled()
        assertEquals(true, flags.last()) // still protected: the setting's share is open
        counter.release()
        assertEquals(false, flags.last())
    }

    @Test
    fun aReleaseWithNothingOpenNeverLowersAnotherShare() {
        counter.release()
        assertEquals(listOf(false), flags)
        counter.acquire()
        counter.release()
        counter.release()
        counter.acquire()
        assertEquals(true, flags.last())
    }

    @Test
    fun overlappingScreensLeaveTheFlagOnUntilTheLastOneGoes() {
        counter.acquire() // the setting
        counter.acquire() // the invite screen
        counter.release() // the invite screen leaves
        assertEquals(true, flags.last())
        counter.release() // the setting is switched off
        assertEquals(false, flags.last())
        counter.acquire() // and on again
        assertEquals(true, flags.last())
    }

    @Test
    fun whenTheStoredChoiceIsOffEverythingIsReleased() {
        counter.holdUntilSettled()
        counter.acquire() // the effect of the initial default (on)
        counter.release() // the loaded choice is off: the effect lets go
        counter.settled() // and the start-up hold
        assertEquals(false, flags.last())
    }
}
