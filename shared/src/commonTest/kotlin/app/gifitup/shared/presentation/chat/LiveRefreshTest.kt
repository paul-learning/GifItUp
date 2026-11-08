package app.gifitup.shared.presentation.chat

import app.gifitup.shared.domain.contacts.KeyBytes
import app.gifitup.shared.domain.events.Wake
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LiveRefreshTest {
    private fun id(n: Int) = KeyBytes(ByteArray(16) { n.toByte() })

    private class Recorder(
        val known: List<KeyBytes>,
        var open: KeyBytes? = null,
        val now: () -> Long,
    ) : LiveRefresh.Target {
        val calls = mutableListOf<Pair<Long, String>>()

        override suspend fun knownGroups() = known

        override fun openGroup() = open

        override suspend fun refreshOpen() {
            calls += now() to "open"
        }

        override suspend fun refreshGroup(group: KeyBytes) {
            calls += now() to "group ${group.bytes[0]}"
        }

        override suspend fun refreshWelcomes() {
            calls += now() to "welcomes"
        }
    }

    private fun TestScope.setup(
        known: List<KeyBytes> = emptyList(),
        open: KeyBytes? = null,
    ): Triple<Channel<Wake>, Recorder, LiveRefresh> {
        val wakes = Channel<Wake>(Channel.UNLIMITED)
        val target = Recorder(known, open) { currentTime }
        val refresh = LiveRefresh(backgroundScope, { wakes.receive() }, target)
        refresh.start()
        return Triple(wakes, target, refresh)
    }

    @Test
    fun aHintReadsItsGroupAndTheOpenConversationGoesThroughItsOwnRead() =
        runTest {
            val (wakes, target, _) = setup(open = id(1))
            wakes.send(Wake(setOf(id(2), id(1)), welcomes = false, everything = false))
            runCurrent()
            advanceTimeBy(5_000)
            assertEquals(listOf("open", "group 2"), target.calls.map { it.second })
        }

    @Test
    fun welcomesComeFirst() =
        runTest {
            val (wakes, target, _) = setup()
            wakes.send(Wake(setOf(id(3)), welcomes = true, everything = false))
            runCurrent()
            advanceTimeBy(5_000)
            assertEquals(listOf("welcomes", "group 3"), target.calls.map { it.second })
        }

    @Test
    fun aHostileStreamCostsAboutOneReadASecond() =
        runTest {
            val everyGroup = (1..60).map(::id)
            val (wakes, target, _) = setup(known = everyGroup, open = id(1))
            // Every group named at once, then a resync every 5 s for a minute.
            wakes.send(Wake(everyGroup.toSet(), welcomes = false, everything = false))
            for (second in 0 until 60 step 5) {
                wakes.send(Wake(emptySet(), welcomes = false, everything = true))
                advanceTimeBy(5_000)
            }
            runCurrent()
            val windows = target.calls.groupBy { it.first / 1_000 }
            assertTrue(windows.values.all { it.size <= 1 }, "at most one read per second: $windows")
            assertTrue(target.calls.size <= 62, "about one read a second over a minute: ${target.calls.size}")
        }

    @Test
    fun aGroupNamedTwiceIsReadOnceWhileItWaits() =
        runTest {
            val (wakes, target, _) = setup()
            wakes.send(Wake(setOf(id(5), id(6)), welcomes = false, everything = false))
            wakes.send(Wake(setOf(id(5)), welcomes = false, everything = false))
            runCurrent()
            advanceTimeBy(5_000)
            assertEquals(listOf("group 5", "group 6"), target.calls.map { it.second })
        }

    @Test
    fun aFailingReadDoesNotEndTheWorker() =
        runTest {
            val wakes = Channel<Wake>(Channel.UNLIMITED)
            val reads = mutableListOf<String>()
            val target =
                object : LiveRefresh.Target {
                    override suspend fun knownGroups() = emptyList<KeyBytes>()

                    override fun openGroup(): KeyBytes? = null

                    override suspend fun refreshOpen() = Unit

                    override suspend fun refreshGroup(group: KeyBytes) {
                        reads += "group ${group.bytes[0]}"
                        if (group == id(1)) error("a store failure")
                    }

                    override suspend fun refreshWelcomes() = Unit
                }
            LiveRefresh(backgroundScope, { wakes.receive() }, target).start()
            wakes.send(Wake(setOf(id(1), id(2)), welcomes = false, everything = false))
            runCurrent()
            advanceTimeBy(5_000)
            assertEquals(listOf("group 1", "group 2"), reads)
        }

    @Test
    fun stopForgetsWhatWaitsAndReadsNothingMore() =
        runTest {
            val (wakes, target, refresh) = setup()
            wakes.send(Wake((1..5).map(::id).toSet(), welcomes = false, everything = false))
            runCurrent()
            refresh.stop()
            val before = target.calls.size
            advanceTimeBy(10_000)
            assertEquals(before, target.calls.size)
        }
}
