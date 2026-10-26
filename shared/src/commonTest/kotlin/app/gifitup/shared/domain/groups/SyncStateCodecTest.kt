package app.gifitup.shared.domain.groups

import app.gifitup.shared.data.contacts.KeyBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SyncStateCodecTest {
    private fun id(n: Int) = KeyBytes(ByteArray(16) { n.toByte() })

    private fun record(
        cursor: Long = 7,
        status: GroupStatus = GroupStatus.ACTIVE,
    ) = GroupRecord(
        cursor,
        status,
        mapOf(9L to 2),
        listOf(4L, 5L),
        listOf(KeyBytes(ByteArray(32) { 3 })),
        ByteArray(16) { 1 },
        null,
    )

    private fun sample() = SyncState(mapOf(id(1) to record(), id(2) to record(cursor = 0, status = GroupStatus.FROZEN_GAP)))

    @Test
    fun aStateRoundTrips() {
        val decoded = SyncStateCodec.decode(SyncStateCodec.encode(sample()))
        assertEquals(setOf(id(1), id(2)), decoded.groups.keys)
        val first = decoded.groups.getValue(id(1))
        assertEquals(7, first.cursor)
        assertEquals(mapOf(9L to 2), first.failures)
        assertEquals(listOf(4L, 5L), first.quarantined)
        assertEquals(listOf(KeyBytes(ByteArray(32) { 3 })), first.unverified)
        assertContentEquals(ByteArray(16) { 1 }, first.lastMsgId)
        assertEquals(null, first.prevMsgId)
        assertEquals(GroupStatus.FROZEN_GAP, decoded.groups.getValue(id(2)).status)
    }

    @Test
    fun noStateIsEmptyState() {
        assertTrue(SyncStateCodec.decode(ByteArray(0)).groups.isEmpty())
    }

    @Test
    fun everyTruncationAndTrailingByteIsRefused() {
        val bytes = SyncStateCodec.encode(sample())
        for (len in 1 until bytes.size) {
            assertFailsWith<SyncStateException>("length $len") { SyncStateCodec.decode(bytes.copyOf(len)) }
        }
        assertFailsWith<SyncStateException> { SyncStateCodec.decode(bytes + 0) }
    }

    @Test
    fun unknownVersionsStatusesAndOversizedCountsAreRefused() {
        val bytes = SyncStateCodec.encode(sample())
        assertFailsWith<SyncStateException> { SyncStateCodec.decode(bytes.copyOf().also { it[0] = 2 }) }
        val statusAt = 1 + 2 + 1 + 16 + Long.SIZE_BYTES
        assertFailsWith<SyncStateException> {
            SyncStateCodec.decode(
                bytes.copyOf().also { it[statusAt] = GroupStatus.entries.size.toByte() },
            )
        }
        val tooManyGroups = bytes.copyOf().also { it[2] = (SyncStateCodec.MAX_GROUPS + 1).toByte() }
        assertFailsWith<SyncStateException> { SyncStateCodec.decode(tooManyGroups) }
    }

    @Test
    fun aDuplicateGroupIsRefused() {
        val one = SyncStateCodec.encode(SyncState(mapOf(id(1) to record())))
        val group = one.copyOfRange(3, one.size)
        val twice = byteArrayOf(SyncStateCodec.VERSION.toByte(), 0, 2) + group + group
        assertFailsWith<SyncStateException> { SyncStateCodec.decode(twice) }
    }

    @Test
    fun anOversizedRecordIsNotWritten() {
        val bloated = record().copy(quarantined = List(SyncStateCodec.MAX_QUARANTINED + 1) { it.toLong() })
        assertFailsWith<SyncStateException> { SyncStateCodec.encode(SyncState(mapOf(id(1) to bloated))) }
    }
}
