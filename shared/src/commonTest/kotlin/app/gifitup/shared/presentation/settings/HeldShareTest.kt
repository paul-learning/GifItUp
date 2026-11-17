package app.gifitup.shared.presentation.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HeldShareTest {
    private val flags = mutableListOf<Boolean>()
    private val counter = WindowProtectionCounter { flags += it }
    private val share = HeldShare(counter::acquire, counter::release)

    @Test
    fun takesAndReleasesOnceHoweverOftenItIsSet() {
        share.set(true)
        share.set(true)
        assertTrue(flags.last())
        share.set(false)
        share.set(false)
        assertFalse(flags.last())
        assertEquals(listOf(true, false), flags)
    }

    /** The bug of the first version: the switch is turned off and the window stays protected. */
    @Test
    fun theSwitchOffLetsGoOfTheProtectionAndOnTakesItAgain() {
        counter.holdUntilSettled() // the Activity's start-up hold
        share.set(true) // the default is on until the stored choice is read
        counter.settled() // the choice is known
        assertTrue(flags.last()) // protected: the share is held
        share.set(false) // the user turns the switch off
        assertFalse(flags.last())
        share.set(true) // and on again
        assertTrue(flags.last())
        share.set(false)
        assertFalse(flags.last())
    }

    @Test
    fun aStoredOffChoiceAtStartEndsUnprotected() {
        counter.holdUntilSettled()
        share.set(true) // first composition, before the stored choice is read
        share.set(false) // the stored choice is "off"
        counter.settled()
        assertFalse(flags.last())
    }

    @Test
    fun leavingTheScreenReleasesWhatIsHeldAndNothingElse() {
        share.set(false) // never held
        assertEquals(listOf<Boolean>(), flags)
        share.set(true)
        share.set(false) // disposed
        assertFalse(flags.last())
    }
}
