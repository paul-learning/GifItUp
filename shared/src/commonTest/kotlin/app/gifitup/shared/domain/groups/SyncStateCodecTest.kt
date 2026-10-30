package app.gifitup.shared.domain.groups

import app.gifitup.shared.data.network.MessageKind
import app.gifitup.shared.domain.contacts.KeyBytes
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
        listOf(OwnSend(12, ByteArray(32) { 1 }), OwnSend(13, ByteArray(32) { 2 })),
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
        assertEquals(null, first.pending)
        assertEquals(listOf(12L, 13L), first.ownSeqs.map { it.seq })
        assertContentEquals(ByteArray(32) { 2 }, first.ownSeqs.last().tail)
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
        assertFailsWith<SyncStateException> { SyncStateCodec.decode(bytes.copyOf().also { it[0] = 4 }) }
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
    fun repeatedFailuresQuarantinesOrKeysAreRefused() {
        val key = KeyBytes(ByteArray(32) { 3 })
        for (bad in listOf(
            record().copy(quarantined = listOf(4L, 4L)),
            record().copy(unverified = listOf(key, key)),
        )) {
            val encoded = SyncStateCodec.encode(SyncState(mapOf(id(1) to bad)))
            assertFailsWith<SyncStateException> { SyncStateCodec.decode(encoded) }
        }
    }

    private fun commit(
        added: List<String> = listOf(uuid(1)),
        welcome: ByteArray? = ByteArray(300) { 5 },
    ) = PendingSend(ByteArray(16) { 9 }, MessageKind.COMMIT, 4, added, listOf(uuid(2)), welcome, ByteArray(500) { 6 })

    private fun uuid(n: Int) = "00000000-0000-0000-0000-" + n.toString().padStart(12, '0')

    @Test
    fun aPendingMessageRoundTripsWithEveryField() {
        val application =
            PendingSend(ByteArray(16) { 3 }, MessageKind.APPLICATION, null, emptyList(), emptyList(), null, ByteArray(1100) { 4 })
        for (pending in listOf(application, commit())) {
            val state = SyncState(mapOf(id(1) to record().copy(pending = pending)))
            val back =
                SyncStateCodec
                    .decode(SyncStateCodec.encode(state))
                    .groups
                    .getValue(id(1))
                    .pending
            assertEquals(pending.kind, back?.kind)
            assertEquals(pending.epoch, back?.epoch)
            assertEquals(pending.added, back?.added)
            assertEquals(pending.removed, back?.removed)
            assertContentEquals(pending.welcome, back?.welcome)
            assertContentEquals(pending.payload, back?.payload)
            assertContentEquals(pending.clientMsgId, back?.clientMsgId)
        }
    }

    @Test
    fun aPendingMessageThatBreaksItsKindsShapeIsRefused() {
        val badApplication = PendingSend(ByteArray(16), MessageKind.APPLICATION, 3, emptyList(), emptyList(), null, ByteArray(10))
        val duplicateDevices = commit(added = listOf(uuid(1), uuid(1)))
        val notAUuid = commit(added = listOf("device-1"))
        for (bad in listOf(badApplication, notAUuid)) {
            val encoded = SyncStateCodec.encode(SyncState(mapOf(id(1) to record().copy(pending = bad))))
            assertFailsWith<SyncStateException> { SyncStateCodec.decode(encoded) }
        }
        val encoded = SyncStateCodec.encode(SyncState(mapOf(id(1) to record().copy(pending = duplicateDevices))))
        assertFailsWith<SyncStateException> { SyncStateCodec.decode(encoded) }
    }

    @Test
    fun theWholeStateIsCappedBeforeItIsWritten() {
        val big = commit(welcome = ByteArray(16 * 1024))
        val groups = (1..20).associate { id(it) to record().copy(pending = big) }
        assertFailsWith<SyncStateException> { SyncStateCodec.encode(SyncState(groups)) }
    }

    @Test
    fun anOversizedRecordIsNotWritten() {
        val bloated = record().copy(quarantined = List(SyncStateCodec.MAX_QUARANTINED + 1) { it.toLong() })
        assertFailsWith<SyncStateException> { SyncStateCodec.encode(SyncState(mapOf(id(1) to bloated))) }
    }

    private fun application(reference: ByteArray?) =
        PendingSend(ByteArray(16) { 4 }, MessageKind.APPLICATION, null, emptyList(), emptyList(), null, ByteArray(40) { 9 }, reference)

    @Test
    fun theReferenceOfAPendingSendAndTheLastAcceptedSendRoundTrip() {
        val ref = ByteArray(32) { 6 }
        val sent = ByteArray(32) { 8 }
        val state = SyncState(mapOf(id(1) to record().copy(pending = application(ref), lastSentReference = sent, lastSentSeq = 21)))
        val first = SyncStateCodec.decode(SyncStateCodec.encode(state)).groups.getValue(id(1))
        assertContentEquals(ref, first.pending?.reference)
        assertContentEquals(sent, first.lastSentReference)
        assertEquals(21L, first.lastSentSeq)
        val none = SyncStateCodec.decode(SyncStateCodec.encode(SyncState(mapOf(id(1) to record().copy(pending = application(null))))))
        assertEquals(
            null,
            none.groups
                .getValue(id(1))
                .pending
                ?.reference,
        )
        assertEquals(null, none.groups.getValue(id(1)).lastSentReference)
    }

    @Test
    fun aVersionTwoStateStillReadsWithoutReferences() {
        val v3 = SyncStateCodec.encode(SyncState(mapOf(id(1) to record())))
        // Version 2 lacks the one flag byte the last accepted send adds at the end of the group (none here).
        val v2 = v3.copyOf(v3.size - 1).also { it[0] = 2 }
        val read = SyncStateCodec.decode(v2).groups.getValue(id(1))
        assertEquals(7L, read.cursor)
        assertEquals(null, read.lastSentReference)
        assertEquals(null, read.lastSentSeq)
        assertFailsWith<SyncStateException> { SyncStateCodec.decode(v3.copyOf().also { it[0] = 4 }) }
        assertFailsWith<SyncStateException> { SyncStateCodec.decode(v3.copyOf().also { it[0] = 1 }) }
    }

    @Test
    fun aReferenceOfTheWrongSizeOrOnACommitIsRefused() {
        assertFailsWith<SyncStateException> {
            SyncStateCodec.encode(SyncState(mapOf(id(1) to record().copy(pending = application(ByteArray(31))))))
        }
        val commit =
            PendingSend(ByteArray(16) { 4 }, MessageKind.COMMIT, 3, emptyList(), emptyList(), null, ByteArray(40) { 9 }, ByteArray(32))
        assertFailsWith<SyncStateException> { SyncStateCodec.encode(SyncState(mapOf(id(1) to record().copy(pending = commit)))) }
        assertFailsWith<SyncStateException> {
            SyncStateCodec.encode(SyncState(mapOf(id(1) to record().copy(lastSentReference = ByteArray(32)))))
        }
    }
}
