package app.gifitup.shared.domain.diagnostics

import app.gifitup.shared.data.storage.LocalStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The last [MAX_ENTRIES] entries, kept in the device's [LocalStore] (ADR-0011) under [KEY] so they survive a restart. Plain
 * bytes: coded events, coded details and times, nothing else (see [DiagEvent], [DiagDetail]). One version byte (2), then
 * 13 bytes per entry: the minute as a big-endian `Long`, the event's and the detail's codes as big-endian `Short`s, a count as
 * one byte. The first version's blob (10 bytes per entry, no detail) is still read. A blob that doesn't parse is an empty log,
 * never an error; an unknown event code (a newer build wrote it) skips its entry, an unknown detail code drops only the detail.
 *
 * Times are kept to the minute, and an event with the same detail as the newest entry in the same minute is counted in that
 * entry, not listed again, so a reconnect loop can't flood the log or the storage (#154 follow-up, plan review SEC-2,
 * SEC-5). Nothing is cached: every change
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

    override fun record(
        event: DiagEvent,
        detail: DiagDetail?,
    ) {
        val at = minute()
        scope.launch { mutex.withLock { if (!stopped) append(DiagEntry(at, event, detail)) } }
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
        val last = list.lastOrNull()
        // The same event with the same detail in the same minute is counted, not listed again (a retry loop is one line).
        if (last != null && last.sameLineAs(entry)) {
            list[list.lastIndex] = last.copy(count = minOf(last.count + 1, DiagEntry.MAX_COUNT))
        } else {
            list += entry
        }
        while (list.size > MAX_ENTRIES) list.removeAt(0)
        write(list)
    }

    private fun DiagEntry.sameLineAs(other: DiagEntry) = at == other.at && event == other.event && detail == other.detail

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
        const val MAX_ENTRIES = 300
        private const val VERSION: Byte = 2
        private const val VERSION_1: Byte = 1
        private const val ENTRY_BYTES = 13
        private const val ENTRY_BYTES_1 = 10
        private const val BYTE_MASK = 0xFF
        private const val BITS = 8
        private const val EVENT_AT = Long.SIZE_BYTES
        private const val DETAIL_AT = EVENT_AT + 2
        private const val COUNT_AT = DETAIL_AT + 2
        private const val SECONDS_PER_MINUTE = 60L
        private const val MAX_BYTES = 1 + MAX_ENTRIES * ENTRY_BYTES

        // An entry: the minute (8 bytes), the event's code (2), the detail's code (2, 0 for none), the count (1).
        internal fun encode(entries: List<DiagEntry>): ByteArray {
            val out = ByteArray(1 + entries.size * ENTRY_BYTES)
            out[0] = VERSION
            entries.forEachIndexed { i, entry ->
                val base = 1 + i * ENTRY_BYTES
                for (b in 0 until Long.SIZE_BYTES) out[base + b] = (entry.at shr (BITS * (Long.SIZE_BYTES - 1 - b))).toByte()
                putShort(out, base + EVENT_AT, entry.event.code)
                putShort(out, base + DETAIL_AT, entry.detail?.code ?: 0)
                out[base + COUNT_AT] = entry.count.coerceIn(1, DiagEntry.MAX_COUNT).toByte()
            }
            return out
        }

        /** Reads this version's blob and the first version's (no detail, no count); anything else is an empty log. */
        internal fun decode(bytes: ByteArray): List<DiagEntry> {
            val size = bytes.size
            return when {
                size < 1 || size > MAX_BYTES -> {
                    emptyList()
                }

                bytes[0] == VERSION && (size - 1) % ENTRY_BYTES == 0 -> {
                    (0 until (size - 1) / ENTRY_BYTES).mapNotNull { entryAt(bytes, 1 + it * ENTRY_BYTES) }
                }

                bytes[0] == VERSION_1 && (size - 1) % ENTRY_BYTES_1 == 0 -> {
                    (0 until (size - 1) / ENTRY_BYTES_1).mapNotNull { entryAt(bytes, 1 + it * ENTRY_BYTES_1, withDetail = false) }
                }

                else -> {
                    emptyList()
                }
            }
        }

        private fun putShort(
            out: ByteArray,
            at: Int,
            value: Int,
        ) {
            out[at] = (value shr BITS).toByte()
            out[at + 1] = value.toByte()
        }

        private fun short(
            bytes: ByteArray,
            at: Int,
        ): Int = ((bytes[at].toInt() and BYTE_MASK) shl BITS) or (bytes[at + 1].toInt() and BYTE_MASK)

        private fun entryAt(
            bytes: ByteArray,
            base: Int,
            withDetail: Boolean = true,
        ): DiagEntry? {
            var at = 0L
            for (b in 0 until Long.SIZE_BYTES) at = (at shl BITS) or (bytes[base + b].toLong() and BYTE_MASK.toLong())
            val event = DiagEvent.ofCode(short(bytes, base + EVENT_AT))
            // An unknown detail (a newer build wrote it) keeps the event without it; the first version has none.
            val detail = if (withDetail) short(bytes, base + DETAIL_AT).takeIf { it != 0 }?.let(DiagDetail::ofCode) else null
            val count = if (withDetail) (bytes[base + COUNT_AT].toInt() and BYTE_MASK).coerceAtLeast(1) else 1
            return event?.let { DiagEntry(at, it, detail, count) }
        }
    }
}
