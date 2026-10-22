package app.gifitup.shared.data.storage

import app.gifitup.shared.data.contacts.Contact
import app.gifitup.shared.data.contacts.ContactBook
import app.gifitup.shared.data.contacts.ContactStatus
import app.gifitup.shared.data.contacts.KeyBytes
import app.gifitup.shared.data.contacts.Observation
import app.gifitup.shared.data.contacts.TrustConflict
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/** The contact record (ADR-0014): created with the identity, strict to read, and never an empty book by accident. */
class ContactStoreTest {
    private val local = InMemoryLocalStore()
    private val identities = RecordIdentityStore(local)
    private val contacts = RecordContactStore(local, identities)
    private val ann = StoredIdentity("https://e2e.invalid", "ann", byteArrayOf(1), deviceId = null)
    private val accountId = "00000000-0000-4000-8000-000000000001"
    private val deviceId = "00000000-0000-4000-8000-000000000002"

    private fun key(n: Int) = KeyBytes(ByteArray(32) { n.toByte() })

    private fun book(blocked: Boolean = false) =
        ContactBook(
            mapOf(
                accountId to
                    Contact(
                        accountId,
                        "ben",
                        if (blocked) ContactStatus.BLOCKED else ContactStatus.OK,
                        mapOf(key(1) to deviceId),
                        if (blocked) Observation(TrustConflict.KEY_CHANGED, "ben", mapOf(key(2) to deviceId)) else null,
                        10L,
                        Long.MAX_VALUE,
                    ),
            ),
        )

    private suspend fun loaded(): ContactBook? = (contacts.load() as? ContactLoad.Book)?.book

    @Test
    fun theIdentityAndAnEmptyBookAreWrittenInOneAtomicWrite() =
        runTest {
            identities.save(ann)
            assertEquals(setOf(RecordIdentityStore.KEY, RecordContactStore.KEY), local.writes.single().keys)
            assertEquals(0, loaded()?.contacts?.size)
        }

    @Test
    fun aBookRoundTripsWithPendingObservationsAndSurvivesLaterIdentitySaves() =
        runTest {
            identities.save(ann)
            contacts.save(book(blocked = true))
            identities.save(StoredIdentity(ann.origin, ann.username, byteArrayOf(2), "8bbdc3b8-7e61-428b-a97f-054194069109"))
            val back = assertNotNull(loaded()).contacts.getValue(accountId)
            assertEquals(ContactStatus.BLOCKED, back.status)
            assertEquals(Observation(TrustConflict.KEY_CHANGED, "ben", mapOf(key(2) to deviceId)), back.pending)
            assertEquals(Long.MAX_VALUE, back.lastSeen)
        }

    @Test
    fun aMissingRecordNextToAnIdentityIsUnavailableNotEmpty() =
        runTest {
            identities.save(ann)
            local.entries.remove(RecordContactStore.KEY)
            assertIs<ContactLoad.Unavailable>(contacts.load())
        }

    @Test
    fun noIdentityMeansUnavailable() =
        runTest {
            assertIs<ContactLoad.Unavailable>(contacts.load())
            assertFailsWith<LocalStateException> { contacts.save(ContactBook()) }
        }

    @Test
    fun aDamagedRecordIsUnavailableAndAnIdentitySaveDoesNotReplaceIt() =
        runTest {
            identities.save(ann)
            val good = ContactRecordCodec.encode(book(), ann.origin, ann.username)
            val damaged =
                listOf(
                    byteArrayOf(),
                    byteArrayOf(2) + good.copyOfRange(1, good.size), // unknown version
                    good.copyOf(good.size - 1), // truncated
                    good + byteArrayOf(0), // trailing byte
                    ByteArray(ContactRecordCodec.MAX_BYTES + 1), // oversize
                    good.copyOf().also { it[it.size - 1] = 9 }, // bad pending flag
                )
            damaged.forEach {
                local.entries[RecordContactStore.KEY] = it
                assertIs<ContactLoad.Unavailable>(contacts.load())
                identities.save(ann)
                assertEquals(it.toList(), local.entries.getValue(RecordContactStore.KEY).toList(), "never replaced by an empty book")
            }
        }

    @Test
    fun aRecordOfAnotherIdentityOrOriginIsUnavailable() =
        runTest {
            identities.save(ann)
            contacts.save(book())
            local.entries[RecordContactStore.KEY] = ContactRecordCodec.encode(book(), ann.origin, "bob")
            assertIs<ContactLoad.Unavailable>(contacts.load())
            local.entries[RecordContactStore.KEY] = ContactRecordCodec.encode(book(), "https://other.invalid", ann.username)
            assertIs<ContactLoad.Unavailable>(contacts.load())
        }

    @Test
    fun clearingTheIdentityDeletesTheBookToo() =
        runTest {
            identities.save(ann)
            identities.clear()
            assertEquals(emptySet(), local.entries.keys)
        }
}
