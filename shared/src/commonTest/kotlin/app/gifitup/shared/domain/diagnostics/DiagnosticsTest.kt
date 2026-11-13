package app.gifitup.shared.domain.diagnostics

import app.gifitup.shared.data.storage.InMemoryLocalStore
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
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
            assertEquals(StoredDiagnosticsLog.MAX_ENTRIES * 13 + 1, store.entries[StoredDiagnosticsLog.KEY]?.size)
        }

    @Test
    fun aDamagedBlobIsAnEmptyLogAndAnUnknownCodeIsSkipped() =
        runTest {
            store.entries[StoredDiagnosticsLog.KEY] = byteArrayOf(9, 9, 9)
            assertTrue(log(this).entries().isEmpty())
            val good = StoredDiagnosticsLog.encode(listOf(DiagEntry(5, DiagEvent.APP_START)))
            val unknown = good + ByteArray(8) + byteArrayOf(0x7F, 0x7F, 0, 0, 1)
            store.entries[StoredDiagnosticsLog.KEY] = unknown
            assertEquals(listOf(DiagEntry(5, DiagEvent.APP_START)), log(this).entries())
        }

    @Test
    fun theSameEventInTheSameMinuteIsCountedNotListedAgain() =
        runTest {
            val log = log(this)
            repeat(50) { log.record(DiagEvent.SOCKET_LOST) }
            clock += 10
            log.record(DiagEvent.SOCKET_LOST)
            advanceUntilIdle()
            assertEquals(1, log.entries().size)
            assertEquals(51, log.entries().single().count)
            log.record(DiagEvent.CALL_FAILED_READ, DiagDetail.API_NETWORK)
            log.record(DiagEvent.CALL_FAILED_READ, DiagDetail.API_RATE_LIMITED)
            advanceUntilIdle()
            assertEquals(3, log.entries().size)
            clock += 120
            log.record(DiagEvent.SOCKET_LOST)
            advanceUntilIdle()
            assertEquals(4, log.entries().size)
        }

    @Test
    fun detailsAndCountsSurviveTheStoredFormatAndTheFirstVersionIsStillRead() {
        val entries =
            listOf(DiagEntry(960, DiagEvent.CALL_FAILED_SEND, DiagDetail.API_GROUP_FULL, 7), DiagEntry(1_020, DiagEvent.APP_START))
        assertEquals(entries, StoredDiagnosticsLog.decode(StoredDiagnosticsLog.encode(entries)))
        val v1 = byteArrayOf(1) + ByteArray(8) + byteArrayOf(0, 2) // time 0, SOCKET_CONNECTED
        assertEquals(listOf(DiagEntry(0, DiagEvent.SOCKET_CONNECTED)), StoredDiagnosticsLog.decode(v1))
        // An unknown detail code drops the detail, not the entry.
        val odd =
            StoredDiagnosticsLog.encode(listOf(DiagEntry(0, DiagEvent.SEND_FAILED))).also {
                it[1 + 8 + 2] = 0x7F
                it[1 + 8 + 3] = 0x7F
            }
        assertEquals(listOf(DiagEntry(0, DiagEvent.SEND_FAILED)), StoredDiagnosticsLog.decode(odd))
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
    fun afterStopNothingIsWrittenAndTheStoreStaysEmpty() =
        runTest {
            val log = log(this)
            log.record(DiagEvent.APP_START)
            advanceUntilIdle()
            log.stop()
            store.entries.clear() // the wipe
            log.record(DiagEvent.LOGIN_FAILED)
            log.clear()
            advanceUntilIdle()
            assertTrue(store.entries.isEmpty())
            assertTrue(log.entries().isEmpty())
        }

    @Test
    fun anOversizedBlobIsRefused() =
        runTest {
            store.entries[StoredDiagnosticsLog.KEY] = ByteArray(StoredDiagnosticsLog.MAX_ENTRIES * 13 + 14).also { it[0] = 2 }
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
                    override suspend fun putAll(entries: Map<String, ByteArray?>) = error("disk")
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
        assertEquals(
            DiagEvent.entries.size,
            DiagEvent.entries
                .map { it.code }
                .toSet()
                .size,
        )
        DiagEvent.entries.forEach { assertEquals(it, DiagEvent.ofCode(it.code)) }
    }

    @Test
    fun detailCodesAreUniqueAndLabelsPlain() {
        assertEquals(
            DiagDetail.entries.size,
            DiagDetail.entries
                .map { it.code }
                .toSet()
                .size,
        )
        DiagDetail.entries.forEach {
            assertTrue(it.code > 0)
            assertTrue(Regex("^[a-z][a-z :-]{1,40}$").matches(it.label), it.label)
            assertEquals(it, DiagDetail.ofCode(it.code))
        }
    }

    @Test
    fun theReportHasOnlyFixedWordsNumbersAndTimes() {
        val info = DiagInfo("0.1.7", "Android 15", customServer = true, chats = DiagInfo.bucket(3), liveConnected = false)
        val entries =
            DiagEvent.entries.mapIndexed { i, e ->
                DiagEntry(
                    1_794_000_000L + i,
                    e,
                    DiagDetail.entries[i % DiagDetail.entries.size],
                    1 + i % 3,
                )
            }
        val report = DiagnosticsReport.render(info, entries, 1_794_000_100L)
        assertTrue("server: custom" in report)
        assertFalse("http" in report)
        // Every event line is "<time>  <label>" with a label from the closed set.
        val labels = DiagEvent.entries.map { it.label }.toSet()
        val lines =
            report
                .lines()
                .dropWhile { !it.startsWith("events") }
                .drop(1)
                .filter { it.isNotBlank() }
        assertEquals(DiagEvent.entries.size, lines.size)
        val details = DiagDetail.entries.map { it.label }.toSet()
        val grammar = Regex("^[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9:]{8}  ([a-z :-]+): ([a-z :-]+?)(  x[0-9]+)?$")
        lines.forEach {
            val m = assertNotNull(grammar.matchEntire(it), it)
            assertTrue(m.groupValues[1] in labels && m.groupValues[2] in details, it)
        }
    }

    @Test
    fun timesAreRenderedInUtc() {
        assertEquals("1970-01-01 00:00:00", DiagnosticsReport.utc(0))
        assertEquals("2026-11-13 12:34:56", DiagnosticsReport.utc(1_794_573_296))
        assertEquals("2000-02-29 23:59:59", DiagnosticsReport.utc(951_868_799))
    }
}
