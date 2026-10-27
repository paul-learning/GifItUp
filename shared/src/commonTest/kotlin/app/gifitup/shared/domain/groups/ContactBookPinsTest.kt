package app.gifitup.shared.domain.groups

import app.gifitup.shared.data.storage.InMemoryContactStore
import app.gifitup.shared.domain.contacts.Contact
import app.gifitup.shared.domain.contacts.ContactBook
import app.gifitup.shared.domain.contacts.ContactBookAccess
import app.gifitup.shared.domain.contacts.ContactStatus
import app.gifitup.shared.domain.contacts.KeyBytes
import app.gifitup.shared.domain.contacts.Observation
import app.gifitup.shared.domain.contacts.TrustConflict
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ContactBookPinsTest {
    private val pinned = ByteArray(32) { 1 }
    private val other = ByteArray(32) { 2 }

    private fun contact(
        name: String,
        status: ContactStatus,
        id: String = name,
    ) = Contact(
        id,
        name,
        status,
        mapOf(KeyBytes(pinned) to "device-$name"),
        if (status == ContactStatus.BLOCKED) Observation(TrustConflict.KEY_CHANGED, name, emptyMap()) else null,
        0,
        0,
    )

    private suspend fun pins(vararg contacts: Contact): ContactPins? {
        val book = ContactBook(contacts.associateBy { it.accountId })
        return ContactBookPins(ContactBookAccess(InMemoryContactStore(book)) { 0 }).load()
    }

    @Test
    fun aNameThatIsNoContactIsAStranger() =
        runTest {
            assertEquals(PinCheck.STRANGER, pins()?.check("zoe", pinned))
        }

    @Test
    fun anOkContactIsPinnedOnItsKeyAndAMismatchOnAnyOther() =
        runTest {
            val pins = pins(contact("ann", ContactStatus.OK))
            assertEquals(PinCheck.PINNED, pins?.check("ann", pinned))
            assertEquals(PinCheck.MISMATCH, pins?.check("ann", other))
        }

    @Test
    fun aBlockedOrGoneNameIsAMismatchEvenOnAPinnedKey() =
        runTest {
            assertEquals(PinCheck.MISMATCH, pins(contact("ben", ContactStatus.BLOCKED))?.check("ben", pinned))
            assertEquals(PinCheck.MISMATCH, pins(contact("cat", ContactStatus.GONE))?.check("cat", pinned))
        }

    @Test
    fun aContactOnlyTheMemberListMentionedHasNoPinYet() =
        runTest {
            assertEquals(PinCheck.STRANGER, pins(contact("dan", ContactStatus.UNCONFIRMED))?.check("dan", other))
        }

    @Test
    fun aMissingOrDamagedBookIsNoLookupAtAll() =
        runTest {
            assertNull(ContactBookPins(ContactBookAccess(InMemoryContactStore(null)) { 0 }).load())
        }
}
