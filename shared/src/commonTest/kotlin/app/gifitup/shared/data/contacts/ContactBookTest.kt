package app.gifitup.shared.data.contacts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The rules of ADR-0014 as a table: every conflict blocks, nothing overwrites a pin, BLOCKED is sticky. */
class ContactBookTest {
    private fun key(n: Int) = KeyBytes(ByteArray(32) { if (it == 0) n.toByte() else (n shr 8).toByte() })

    private fun id(n: Int) = "00000000-0000-4000-8000-" + n.toString().padStart(12, '0')

    private fun seen(
        account: Int,
        name: String,
        vararg keys: Int,
    ) = Sighting(id(account), name, keys.associate { key(it) to id(1000 + it) })

    private fun ContactBook.claimOk(s: Sighting): ContactBook {
        val (book, verdict) = claim(s, NOW)
        assertEquals(Verdict.Usable, verdict)
        return book
    }

    private fun ContactBook.claimBlocked(
        s: Sighting,
        reason: TrustConflict,
    ): ContactBook {
        val (book, verdict) = claim(s, NOW)
        assertEquals(Verdict.Blocked(reason), verdict)
        return book
    }

    private val pinnedAlice = ContactBook().claimOk(seen(1, "alice", 1))

    @Test
    fun firstSightOfAClaimPinsTheKeyAndItIsUsableAgain() {
        val contact = pinnedAlice.contacts.getValue(id(1))
        assertEquals(ContactStatus.OK, contact.status)
        assertEquals(setOf(key(1)), contact.devices.keys)
        pinnedAlice.claimOk(seen(1, "alice", 1))
    }

    @Test
    fun aDifferentKeyUnderTheSameDeviceIdIsKeyChanged() {
        val s = Sighting(id(1), "alice", mapOf(key(2) to id(1001)))
        val book = pinnedAlice.claimBlocked(s, TrustConflict.KEY_CHANGED)
        assertEquals(
            setOf(key(1)),
            book.contacts
                .getValue(id(1))
                .devices.keys,
            "the pin is untouched",
        )
        assertEquals(ContactStatus.BLOCKED, book.contacts.getValue(id(1)).status)
    }

    @Test
    fun anAdditionalDeviceIsBlockedUntilMultiDevice() {
        val book = pinnedAlice.claimBlocked(seen(1, "alice", 1, 2), TrustConflict.DEVICE_ADDED)
        assertEquals(
            setOf(key(1)),
            book.contacts
                .getValue(id(1))
                .devices.keys,
        )
    }

    @Test
    fun aNewNameForTheSameAccountIsRenamed() {
        pinnedAlice.claimBlocked(seen(1, "alicia", 1), TrustConflict.RENAMED)
    }

    @Test
    fun aNameThatMovedToAnotherAccountIsReassignedAndTheOldOneIsGone() {
        val book = pinnedAlice.claimBlocked(seen(2, "alice", 2), TrustConflict.USERNAME_REASSIGNED)
        assertEquals(ContactStatus.GONE, book.contacts.getValue(id(1)).status)
        assertEquals(
            setOf(key(1)),
            book.contacts
                .getValue(id(1))
                .devices.keys,
            "its pins are kept",
        )
        assertEquals(ContactStatus.BLOCKED, book.contacts.getValue(id(2)).status)
        assertEquals(TrustConflict.USERNAME_REASSIGNED, book.blockedReasonFor("alice"))
    }

    @Test
    fun aKeyPinnedToAnotherAccountIsAConflictWhateverTheNameOrDeviceId() {
        pinnedAlice.claimBlocked(Sighting(id(2), "bob", mapOf(key(1) to id(5))), TrustConflict.KEY_CONFLICT)
        pinnedAlice.claimBlocked(Sighting(id(2), "alice", mapOf(key(1) to id(1001))), TrustConflict.KEY_CONFLICT)
    }

    @Test
    fun aFirstClaimWithSeveralDevicesIsBlockedAndPinsNothing() {
        val (book, verdict) = ContactBook().claim(seen(3, "carol", 3, 4), NOW)
        assertEquals(Verdict.Blocked(TrustConflict.MULTIPLE_DEVICES), verdict)
        assertTrue(
            book.contacts
                .getValue(id(3))
                .devices
                .isEmpty(),
        )
    }

    @Test
    fun blockedIsStickyAndAGoodSightingDoesNotClearIt() {
        val blocked = pinnedAlice.claimBlocked(seen(1, "alicia", 1), TrustConflict.RENAMED)
        val again = blocked.claimBlocked(seen(1, "alice", 1), TrustConflict.RENAMED)
        assertEquals(
            ContactStatus.BLOCKED,
            again
                .list(listOf(seen(1, "alice", 1)), true, NOW)
                .contacts
                .getValue(id(1))
                .status,
        )
    }

    @Test
    fun aConflictingSightingNeverChangesThePinOnlyThePendingObservation() {
        val first = pinnedAlice.claimBlocked(seen(1, "alicia", 1), TrustConflict.RENAMED)
        val second = first.claimBlocked(Sighting(id(1), "alice", mapOf(key(9) to id(1001))), TrustConflict.KEY_CHANGED)
        val contact = second.contacts.getValue(id(1))
        assertEquals("alice", contact.username)
        assertEquals(setOf(key(1)), contact.devices.keys)
        assertEquals(TrustConflict.KEY_CHANGED, contact.pending?.reason)
    }

    @Test
    fun acceptPinsExactlyTheObservationThatWasShown() {
        val blocked = pinnedAlice.claimBlocked(Sighting(id(1), "alice", mapOf(key(2) to id(1001))), TrustConflict.KEY_CHANGED)
        val shown = checkNotNull(blocked.contacts.getValue(id(1)).pending)
        val (book, result) = blocked.accept(id(1), shown)
        assertEquals(AcceptResult.Accepted, result)
        assertEquals(
            setOf(key(2)),
            book.contacts
                .getValue(id(1))
                .devices.keys,
        )
        assertEquals(ContactStatus.OK, book.contacts.getValue(id(1)).status)
        assertNull(book.contacts.getValue(id(1)).pending)
        book.claimOk(Sighting(id(1), "alice", mapOf(key(2) to id(1001))))
    }

    @Test
    fun acceptRefusesWhenThePendingObservationChangedAfterItWasShown() {
        val blocked = pinnedAlice.claimBlocked(Sighting(id(1), "alice", mapOf(key(2) to id(1001))), TrustConflict.KEY_CHANGED)
        val shown = checkNotNull(blocked.contacts.getValue(id(1)).pending)
        val changed = blocked.claimBlocked(Sighting(id(1), "alice", mapOf(key(3) to id(1001))), TrustConflict.KEY_CHANGED)
        val (book, result) = changed.accept(id(1), shown)
        assertEquals(AcceptResult.Changed, result)
        assertEquals(
            setOf(key(1)),
            book.contacts
                .getValue(id(1))
                .devices.keys,
        )
    }

    @Test
    fun acceptRefusesWhatIsNotBlockedAndAKeyConflict() {
        assertEquals(AcceptResult.Refused, pinnedAlice.accept(id(1), Observation(TrustConflict.RENAMED, "x", emptyMap())).second)
        assertEquals(AcceptResult.Refused, pinnedAlice.accept(id(7), Observation(TrustConflict.RENAMED, "x", emptyMap())).second)
        val conflict = pinnedAlice.claimBlocked(Sighting(id(2), "bob", mapOf(key(1) to id(5))), TrustConflict.KEY_CONFLICT)
        val shown = checkNotNull(conflict.contacts.getValue(id(2)).pending)
        assertEquals(AcceptResult.Refused, conflict.accept(id(2), shown).second, "a key can't be pinned to two accounts")
    }

    @Test
    fun theListNeverPinsAndAClaimReplacesAnUnconfirmedEntryOfTheSameName() {
        val listed = ContactBook().list(listOf(seen(9, "alice", 9)), true, NOW)
        assertEquals(ContactStatus.UNCONFIRMED, listed.contacts.getValue(id(9)).status)
        assertEquals(0, listed.pinCount)
        val claimed = listed.claimOk(seen(1, "alice", 1))
        assertNull(claimed.contacts[id(9)], "the real alice wins, with no block")
        assertEquals(ContactStatus.OK, claimed.contacts.getValue(id(1)).status)
    }

    @Test
    fun aListedNameHeldByAPinDoesNotMarkThePinGoneOrBlockAnything() {
        val book = pinnedAlice.list(listOf(seen(1, "alice", 1), seen(2, "alice", 2)), true, NOW)
        assertEquals(ContactStatus.OK, book.contacts.getValue(id(1)).status)
        assertEquals(ContactStatus.UNCONFIRMED, book.contacts.getValue(id(2)).status)
    }

    @Test
    fun aListedUnknownAccountWithAPinnedKeyIsAConflictNotUnconfirmed() {
        val book = pinnedAlice.list(listOf(seen(2, "mallory", 1)), complete = false, NOW)
        assertEquals(ContactStatus.BLOCKED, book.contacts.getValue(id(2)).status)
        assertEquals(
            TrustConflict.KEY_CONFLICT,
            book.contacts
                .getValue(id(2))
                .pending
                ?.reason,
        )
        assertEquals(ContactStatus.OK, book.contacts.getValue(id(1)).status, "the real one is not blocked by an impostor")
    }

    @Test
    fun aListedKnownAccountIsCheckedLikeAClaim() {
        val renamed = pinnedAlice.list(listOf(seen(1, "alicia", 1)), true, NOW)
        assertEquals(
            TrustConflict.RENAMED,
            renamed.contacts
                .getValue(id(1))
                .pending
                ?.reason,
        )
        val added = pinnedAlice.list(listOf(seen(1, "alice", 1, 2)), true, NOW)
        assertEquals(
            TrustConflict.DEVICE_ADDED,
            added.contacts
                .getValue(id(1))
                .pending
                ?.reason,
        )
    }

    @Test
    fun absenceCountsOnlyInACompleteList() {
        val cut = pinnedAlice.list(listOf(seen(2, "bob", 2)), complete = false, NOW)
        assertEquals(ContactStatus.OK, cut.contacts.getValue(id(1)).status, "a cut list proves nothing")
        val complete = pinnedAlice.list(listOf(seen(2, "bob", 2)), complete = true, NOW)
        assertEquals(ContactStatus.GONE, complete.contacts.getValue(id(1)).status)
        val back = complete.list(listOf(seen(1, "alice", 1)), complete = true, NOW)
        assertEquals(ContactStatus.OK, back.contacts.getValue(id(1)).status, "gone returns to ok with identical pins")
    }

    @Test
    fun unconfirmedEntriesAreResetByACompleteListButNotByACutOne() {
        val listed = ContactBook().list(listOf(seen(9, "zed", 9)), true, NOW)
        assertEquals(1, listed.list(emptyList(), complete = false, NOW).contacts.size)
        assertEquals(0, listed.list(emptyList(), complete = true, NOW).contacts.size)
    }

    @Test
    fun unconfirmedEntriesHaveTheirOwnCapAndNeverEvictAPin() {
        val sightings = (10 until 10 + ContactBook.MAX_UNCONFIRMED + 20).map { seen(it, "user$it", it) }
        val book = pinnedAlice.list(sightings, true, NOW)
        assertEquals(ContactBook.MAX_UNCONFIRMED, book.contacts.values.count { it.status == ContactStatus.UNCONFIRMED })
        assertEquals(1, book.pinCount)
    }

    @Test
    fun atThePinCapANewAccountIsRefusedAndNothingIsEvicted() {
        var book = ContactBook()
        repeat(ContactBook.MAX_PINS) { book = book.claimOk(seen(it + 10, "user$it", it + 10)) }
        val (full, verdict) = book.claim(seen(5000, "late", 5000), NOW)
        assertEquals(Verdict.Blocked(TrustConflict.BOOK_FULL), verdict)
        assertEquals(ContactBook.MAX_PINS, full.pinCount)
        assertIs<Verdict.Usable>(full.claim(seen(10, "user0", 10), NOW).second)
    }
}

private const val NOW = 1_000L
