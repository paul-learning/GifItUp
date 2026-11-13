package app.gifitup.shared.presentation.diagnostics

import app.gifitup.shared.domain.diagnostics.DiagEntry
import app.gifitup.shared.domain.diagnostics.DiagEvent
import app.gifitup.shared.domain.diagnostics.DiagInfo
import app.gifitup.shared.domain.diagnostics.DiagnosticsLog
import app.gifitup.shared.presentation.chat.ChatProblem
import app.gifitup.shared.presentation.chat.SendProblem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DiagnosticsObserverTest {
    private class Recording : DiagnosticsLog {
        val events = mutableListOf<DiagEvent>()

        override fun record(event: DiagEvent) {
            events += event
        }

        override suspend fun entries() = events.map { DiagEntry(0, it) }

        override suspend fun clear() = events.clear()

        override suspend fun stop() = Unit
    }

    /** A scope for the observers' endless collectors, on the test's scheduler; it is not a child, so the test doesn't wait for it. */
    private fun TestScope.watching() = CoroutineScope(UnconfinedTestDispatcher(testScheduler))

    @Test
    fun theSocketNoteIsConnectedAndLostButNotTheFirstNotConnected() =
        runTest {
            val log = Recording()
            val connected = MutableStateFlow(false)
            DiagnosticsObserver(log, watching()).watchSocket(connected)
            advanceUntilIdle()
            connected.value = true
            advanceUntilIdle()
            connected.value = false
            advanceUntilIdle()
            assertEquals(listOf(DiagEvent.SOCKET_CONNECTED, DiagEvent.SOCKET_LOST), log.events)
        }

    @Test
    fun listAndSendProblemsBecomeCodedEventsAndTheCaptionRuleDoesNot() =
        runTest {
            val log = Recording()
            val list = MutableStateFlow<ChatProblem?>(null)
            val send = MutableStateFlow<SendProblem?>(null)
            val observer = DiagnosticsObserver(log, watching())
            observer.watchList(list)
            observer.watchSend(send)
            advanceUntilIdle()
            list.value = ChatProblem.OFFLINE
            advanceUntilIdle()
            list.value = ChatProblem.HISTORY
            advanceUntilIdle()
            send.value = SendProblem.CAPTION
            advanceUntilIdle()
            send.value = SendProblem.OFFLINE
            advanceUntilIdle()
            assertEquals(listOf(DiagEvent.SYNC_FAILED_NETWORK, DiagEvent.HISTORY_PROBLEM, DiagEvent.SEND_FAILED), log.events)
        }

    @Test
    fun theHolderBuildsTheReportAndDropsItWhenLeft() =
        runTest {
            val log = Recording().also { it.record(DiagEvent.APP_START) }
            val info = DiagInfo("0.1.7", "web", customServer = false, chats = DiagInfo.bucket(0), liveConnected = false)
            val holder = DiagnosticsStateHolder(this, log, { info }, { 0L })
            holder.refresh()
            advanceUntilIdle()
            assertTrue("app start" in assertNotNull(holder.state.value.report))
            holder.leave()
            assertEquals(null, holder.state.value.report)
            holder.refresh()
            advanceUntilIdle()
            holder.clear()
            advanceUntilIdle()
            assertTrue("(none)" in assertNotNull(holder.state.value.report))
        }
}
