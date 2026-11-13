package app.gifitup.shared.domain.diagnostics

import app.gifitup.shared.data.storage.LocalStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The last [MAX_ENTRIES] events, kept in the device's [LocalStore] (ADR-0011) under [KEY] so they survive a restart. Plain
 * bytes: coded events and times, nothing else (see [DiagEvent]). One version byte, then ten bytes per entry: the time as a
 * big-endian `Long`, the event's code as a big-endian `Short`. A blob that doesn't parse is an empty log, never an error; an
 * unknown code (a newer build wrote it) is skipped.
 *
 * Times are kept to the minute, and an event that is the same as the newest entry in the same minute is not noted again, so a
 * reconnect loop can't flood the log or the storage (#154 follow-up, plan review SEC-2, SEC-5). Nothing is cached: every change
 * reads the stored blob, changes it and writes it back, so the store's wipe (which deletes the whole store, ADR-0011) really
 * empties the log and nothing writes old entries back afterwards, and the wipe first calls [stop]. Every change runs under one
 * [Mutex], in the order [record] was called as long as [scope] runs one coroutine at a time (the app's does); a failed read or
 * write is dropped and never recorded as an event itself.
 */
class StoredDiagnosticsLog(
    private val store: LocalStore,
    private val now: () -> Long,
    private val scope: CoroutineScope,
) : DiagnosticsLog {
    private val mutex = Mutex()
    private var stopped = false

    override fun record(event: DiagEvent) {
        val at = minute()
        scope.launch { mutex.withLock { if (!stopped) append(DiagEntry(at, event)) } }
    }

    override suspend fun entries(): List<DiagEntry> = mutex.withLock { read() }

    override suspend fun clear() {
        mutex.withLock { if (!stopped) write(listOf(DiagEntry(minute(), DiagEvent.LOG_CLEARED))) }
    }

    override suspend fun stop() {
        mutex.withLock { stopped = true }
    }

    /** The time to the minute, read from the clock once. */
    private fun minute(): Long = now().let { it - it % SECONDS_PER_MINUTE }

    private suspend fun append(entry: DiagEntry) {
        val list = read().toMutableList()
        if (list.lastOrNull() == entry) return
        list += entry
        while (list.size > MAX_ENTRIES) list.removeAt(0)
        write(list)
    }

    private suspend fun read(): List<DiagEntry> =
        try {
            store.get(KEY)?.let(::decode).orEmpty()
        } catch (_: Exception) {
            emptyList()
        }

    private suspend fun write(list: List<DiagEntry>) {
        try {
            store.putAll(mapOf(KEY to encode(list)))
        } catch (_: Exception) {
            // Diagnostics never break the app; what can't be written is not there.
        }
    }

    companion object {
        const val KEY = "diagnostics.v1"
        const val MAX_ENTRIES = 200
        private const val VERSION: Byte = 1
        private const val ENTRY_BYTES = 10
        private const val BYTE_MASK = 0xFF
        private const val BITS = 8
        private const val SECONDS_PER_MINUTE = 60L
        private const val MAX_BYTES = 1 + MAX_ENTRIES * ENTRY_BYTES

        internal fun encode(entries: List<DiagEntry>): ByteArray {
            val out = ByteArray(1 + entries.size * ENTRY_BYTES)
            out[0] = VERSION
            entries.forEachIndexed { i, entry ->
                val base = 1 + i * ENTRY_BYTES
                for (b in 0 until Long.SIZE_BYTES) out[base + b] = (entry.at shr (BITS * (Long.SIZE_BYTES - 1 - b))).toByte()
                out[base + Long.SIZE_BYTES] = (entry.event.code shr BITS).toByte()
                out[base + Long.SIZE_BYTES + 1] = entry.event.code.toByte()
            }
            return out
        }

        internal fun decode(bytes: ByteArray): List<DiagEntry> {
            val valid = bytes.size in 1..MAX_BYTES && bytes[0] == VERSION && (bytes.size - 1) % ENTRY_BYTES == 0
            return if (!valid) emptyList() else (0 until (bytes.size - 1) / ENTRY_BYTES).mapNotNull { entryAt(bytes, 1 + it * ENTRY_BYTES) }
        }

        private fun entryAt(
            bytes: ByteArray,
            base: Int,
        ): DiagEntry? {
            var at = 0L
            for (b in 0 until Long.SIZE_BYTES) at = (at shl BITS) or (bytes[base + b].toLong() and BYTE_MASK.toLong())
            val high = (bytes[base + Long.SIZE_BYTES].toInt() and BYTE_MASK) shl BITS
            val code = high or (bytes[base + Long.SIZE_BYTES + 1].toInt() and BYTE_MASK)
            return DiagEvent.ofCode(code)?.let { DiagEntry(at, it) }
        }
    }
}
