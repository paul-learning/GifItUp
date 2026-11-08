package app.gifitup.shared.presentation.chat

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class LiveSwitchTest {
    @Test
    fun aStartAfterAStopWaitsForTheStopToFinishAndWins() =
        runTest {
            val log = mutableListOf<String>()
            val switch =
                LiveSwitch(
                    backgroundScope,
                    open = { log += "open" },
                    close = {
                        log += "closing"
                        delay(1_000) // the stop takes a while to end the loop
                        log += "closed"
                    },
                )
            switch.set(true)
            runCurrent()
            switch.set(false)
            switch.set(true) // a quick background-and-back
            runCurrent()
            advanceTimeBy(2_000)
            assertEquals(listOf("open", "closing", "closed", "open"), log)
        }

    @Test
    fun aRequestForTheStateItIsInDoesNothing() =
        runTest {
            val log = mutableListOf<String>()
            val switch = LiveSwitch(backgroundScope, open = { log += "open" }, close = { log += "close" })
            switch.set(false)
            switch.set(true)
            switch.set(true)
            runCurrent()
            assertEquals(listOf("open"), log)
        }
}
