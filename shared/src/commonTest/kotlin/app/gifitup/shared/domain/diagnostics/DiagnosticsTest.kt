package app.gifitup.shared.domain.diagnostics

import app.gifitup.shared.data.storage.InMemoryLocalStore
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiagnosticsTest {
    private val store = InMemoryLocalStore()
    private var clock = 1_000L // seconds; a log keeps minutes

    private fun log(scope: TestScope) = StoredDiagnosticsLog(store, { clock }, scope)

    @Test
    fun recordedEventsSurviveANewLogOverTheSameStore() =
        runTest {
            val first = log(this)
            first.record(DiagEvent.APP_START)
            first.record(DiagEvent.SOCKET_LOST)
            advanceUntilIdle()
            val second = log(this)
            // 1000 s is in the minute that starts at 960.
            assertEquals(listOf(DiagEntry(960, DiagEvent.APP_START), DiagEntry(960, DiagEvent.SOCKET_LOST)), second.entries())
        }

    @Test
    fun onlyTheLastTwoHundredAreKept() =
        runTest {
            val log = log(this)
            repeat(StoredDiagnosticsLog.MAX_ENTRIES + 5) {
                log.record(DiagEvent.SYNC_FAILED_NETWORK)
                clock += 60
            }
            advanceUntilIdle()
            val kept = log.entries()
            assertEquals(StoredDiagnosticsLog.MAX_ENTRIES, kept.size)
            assertEquals(960L + 5 * 60, kept.first().at)
            assertEquals(StoredDiagnosticsLog.MAX_ENTRIES * 10 + 1, store.entries[StoredDiagnosticsLog.KEY]?.size)
        }

    @Test
    fun aDamagedBlobIsAnEmptyLogAndAnUnknownCodeIsSkipped() =
        runTest {
            store.entries[StoredDiagnosticsLog.KEY] = byteArrayOf(9, 9, 9)
            assertTrue(log(this).entries().isEmpty())
            val good = StoredDiagnosticsLog.encode(listOf(DiagEntry(5, DiagEvent.APP_START)))
            val unknown = good + ByteArray(8) + byteArrayOf(0x7F, 0x7F)
            store.entries[StoredDiagnosticsLog.KEY] = unknown
            assertEquals(listOf(DiagEntry(5, DiagEvent.APP_START)), log(this).entries())
        }

    @Test
    fun theSameEventInTheSameMinuteIsNotedOnce() =
        runTest {
            val log = log(this)
            repeat(50) { log.record(DiagEvent.SOCKET_LOST) }
            clock += 10
            log.record(DiagEvent.SOCKET_LOST)
            advanceUntilIdle()
            assertEquals(1, log.entries().size)
            clock += 120
            log.record(DiagEvent.SOCKET_LOST)
            advanceUntilIdle()
            assertEquals(2, log.entries().size)
        }

    @Test
    fun aWipeOfTheStoreEmptiesTheLogAndNothingWritesItBack() =
        runTest {
            val log = log(this)
            log.record(DiagEvent.APP_START)
            advanceUntilIdle()
            store.entries.clear() // what the platform wipe does: the whole store goes
            assertTrue(log.entries().isEmpty())
            log.record(DiagEvent.SOCKET_LOST)
            advanceUntilIdle()
            assertEquals(listOf(DiagEvent.SOCKET_LOST), log.entries().map { it.event })
        }

    @Test
    fun anOversizedBlobIsRefused() =
        runTest {
            store.entries[StoredDiagnosticsLog.KEY] = ByteArray(StoredDiagnosticsLog.MAX_ENTRIES * 10 + 11).also { it[0] = 1 }
            assertTrue(log(this).entries().isEmpty())
        }

    @Test
    fun clearEmptiesTheLogAndNotesItself() =
        runTest {
            val log = log(this)
            log.record(DiagEvent.SEND_FAILED)
            advanceUntilIdle()
            log.clear()
            assertEquals(listOf(DiagEvent.LOG_CLEARED), log.entries().map { it.event })
        }

    @Test
    fun aFailingStoreNeverBreaksTheLog() =
        runTest {
            val broken =
                object : InMemoryLocalStore() {
                    override suspend fun putAll(entries: Map<String, ByteArray?>) = throw IllegalStateException("disk")
                }
            val log = StoredDiagnosticsLog(broken, { 1 }, this)
            log.record(DiagEvent.APP_START)
            advanceUntilIdle()
            // Nothing was written and nothing threw: what can't be stored is simply not there.
            assertTrue(log.entries().isEmpty())
        }

    @Test
    fun eventCodesAreUniqueAndStable() {
        // Labels stay plain lower-case words: the report's line grammar depends on it.
        DiagEvent.entries.forEach { assertTrue(Regex("^[a-z][a-z :-]{1,60}$").matches(it.label), it.label) }
        assertEquals(DiagEvent.entries.size, DiagEvent.entries.map { it.code }.toSet().size)
        DiagEvent.entries.forEach { assertEquals(it, DiagEvent.ofCode(it.code)) }
    }

    @Test
    fun theReportHasOnlyFixedWordsNumbersAndTimes() {
        val info = DiagInfo("0.1.7", "Android 15", customServer = true, signedIn = true, chats = DiagInfo.bucket(3), liveConnected = false)
        val entries = DiagEvent.entries.mapIndexed { i, e -> DiagEntry(1_794_000_000L + i, e) }
        val report = DiagnosticsReport.render(info, entries, 1_794_000_100L)
        assertTrue("server: custom" in report)
        assertFalse("http" in report)
        // Every event line is "<time>  <label>" with a label from the closed set.
        val labels = DiagEvent.entries.map { it.label }.toSet()
        val lines = report.lines().dropWhile { !it.startsWith("events") }.drop(1).filter { it.isNotBlank() }
        assertEquals(DiagEvent.entries.size, lines.size)
        lines.forEach { assertTrue(it.substring(21) in labels, it) }
    }

    @Test
    fun timesAreRenderedInUtc() {
        assertEquals("1970-01-01 00:00:00", DiagnosticsReport.utc(0))
        assertEquals("2026-11-13 12:34:56", DiagnosticsReport.utc(1_794_573_296))
        assertEquals("2000-02-29 23:59:59", DiagnosticsReport.utc(951_868_799))
    }
}
