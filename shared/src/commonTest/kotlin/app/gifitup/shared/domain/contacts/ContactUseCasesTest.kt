package app.gifitup.shared.domain.contacts

import app.gifitup.shared.data.network.ApiError
import app.gifitup.shared.data.network.Base64Url
import app.gifitup.shared.data.network.FakeAuthServer
import app.gifitup.shared.data.network.FakeAuthServer.Companion.DEVICE_ID
import app.gifitup.shared.data.network.FakeAuthServer.Companion.json
import app.gifitup.shared.data.network.Session
import app.gifitup.shared.data.storage.InMemoryContactStore
import app.gifitup.shared.data.storage.StoredIdentity
import app.gifitup.shared.domain.auth.AuthError
import app.gifitup.shared.domain.auth.AuthResult
import app.gifitup.shared.domain.auth.CountingRandom
import app.gifitup.shared.domain.auth.DeviceLock
import app.gifitup.shared.domain.auth.HistoryLeftovers
import app.gifitup.shared.domain.auth.InMemoryIdentityStore
import app.gifitup.shared.domain.auth.InMemoryKeyStore
import app.gifitup.shared.domain.auth.LogIn
import app.gifitup.shared.domain.auth.RegisterDevice
import app.gifitup.shared.domain.auth.unguarded
import app.gifitup.shared.domain.contacts.AcceptResult
import app.gifitup.shared.domain.contacts.Contact
import app.gifitup.shared.domain.contacts.ContactBook
import app.gifitup.shared.domain.contacts.ContactStatus
import app.gifitup.shared.domain.contacts.KeyBytes
import app.gifitup.shared.domain.contacts.Observation
import app.gifitup.shared.domain.contacts.TrustConflict
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** The book's access lock, the list refresh and the accept use case (ADR-0014). */
@OptIn(BlindAcceptForDevCli::class) // The blind accept is the dev CLI's; its behaviour is tested here.
class ContactUseCasesTest {
    private val server = FakeAuthServer()
    private val identities = InMemoryIdentityStore()
    private val keys = InMemoryKeyStore()
    private val store = InMemoryContactStore()
    private val access = ContactBookAccess(store) { 1_000L }
    private val refresh = RefreshContacts(server.api(), unguarded(), identities, keys, DeviceLock(), access)

    private fun id(n: Int) = "00000000-0000-4000-8000-" + n.toString().padStart(12, '0')

    private fun key(n: Int) = Base64Url.encode(ByteArray(32) { n.toByte() })

    private fun member(
        n: Int,
        name: String = "user$n",
        keyText: String = key(n),
    ) = """{"accountId":"${id(
        n,
    )}","username":"$name","relation":"invitee","devices":[{"deviceId":"${id(100 + n)}","signatureKey":"$keyText"}]}"""

    private fun members(
        vararg members: String,
        more: Boolean = false,
    ) = json("""{"members":[${members.joinToString(",")}],"more":$more}""")

    private suspend fun registered(): Session {
        val api = server.api()
        RegisterDevice(
            api,
            unguarded(),
            identities,
            keys,
            CountingRandom(),
            DeviceLock(),
            HistoryLeftovers.None,
        )("ann", "AAAAAAAAAAAAAAAAAAAAAA")
        val stored = identities.identity!!
        identities.identity = StoredIdentity(stored.origin, stored.username, stored.snapshot, DEVICE_ID)
        val session = assertIs<AuthResult.Ok<Session>>(LogIn(api, unguarded(), identities, keys, DeviceLock())()).value
        identities.saved.clear()
        server.requests.clear()
        return session
    }

    private fun contact(n: Int) =
        Contact(id(n), "user$n", ContactStatus.OK, mapOf(KeyBytes(ByteArray(32) { n.toByte() }) to id(100 + n)), null, 1L, 1L)

    @Test
    fun concurrentUpdatesAreSerialisedSoNoPinIsLost() =
        runTest {
            val results =
                (1..20)
                    .map { n -> async { access.update { book, _ -> ContactBook(book.contacts + (id(n) to contact(n))) to n } } }
                    .awaitAll()
            assertEquals(20, results.count { it is BookUpdate.Done })
            assertEquals(20, store.book?.pinCount, "a store that suspends in load and save would lose updates without the lock")
        }

    @Test
    fun anUnreadableBookIsNeverChangedAndASaveFailureChangesNothing() =
        runTest {
            store.book = null
            assertEquals(BookUpdate.Unavailable, access.update { book, _ -> book to Unit })
            store.book = ContactBook()
            store.failSaves = true
            assertEquals(BookUpdate.NotSaved, access.update { book, _ -> ContactBook(book.contacts + (id(1) to contact(1))) to Unit })
            assertEquals(0, store.book?.pinCount)
        }

    @Test
    fun refreshRecordsTheListAsUnconfirmedAndPinsNothing() =
        runTest {
            val session = registered()
            server.on("/members", members(member(1), member(2)))
            val book = assertIs<AuthResult.Ok<ContactBook>>(refresh(session)).value
            assertEquals(setOf(id(1), id(2)), book.contacts.keys)
            assertEquals(0, book.pinCount)
            assertEquals(book.contacts.keys, store.book?.contacts?.keys)
        }

    @Test
    fun refreshRefusesTheWholeListThatNamesThisDeviceAndRecordsNothing() =
        runTest {
            val session = registered()
            server.on("/members", members(member(1), member(2, name = "ann")))
            assertEquals(AuthResult.Failed(AuthError.Api(ApiError.MALFORMED_RESPONSE)), refresh(session))
            val own =
                unguarded()
                    .restore(
                        identities.identity!!.snapshot,
                        keys.load()!!,
                    ).use { Base64Url.encode(it.publicIdentity().signaturePublicKey) }
            server.on("/members", members(member(1), member(3, keyText = own)))
            assertEquals(AuthResult.Failed(AuthError.Api(ApiError.MALFORMED_RESPONSE)), refresh(session))
            assertEquals(0, store.book?.contacts?.size)
        }

    @Test
    fun anUnusableBookMakesNoRequest() =
        runTest {
            val session = registered()
            store.book = null
            assertEquals(AuthResult.Failed(AuthError.Local.CONTACTS_UNAVAILABLE), refresh(session))
            assertEquals(emptyList(), server.requests)
        }

    @Test
    fun aCutListDoesNotMarkAnythingGone() =
        runTest {
            val session = registered()
            store.book = ContactBook(mapOf(id(1) to contact(1)))
            server.on("/members", members(member(2), more = true), members(member(2), more = false))
            refresh(session)
            assertEquals(
                ContactStatus.OK,
                store.book
                    ?.contacts
                    ?.getValue(id(1))
                    ?.status,
            )
            refresh(session)
            assertEquals(
                ContactStatus.GONE,
                store.book
                    ?.contacts
                    ?.getValue(id(1))
                    ?.status,
            )
        }

    @Test
    fun acceptPinsOnlyWhatWasShown() =
        runTest {
            val blocked =
                contact(1).copy(
                    status = ContactStatus.BLOCKED,
                    pending = Observation(TrustConflict.KEY_CHANGED, "user1", mapOf(KeyBytes(ByteArray(32) { 9 }) to id(101))),
                )
            store.book = ContactBook(mapOf(id(1) to blocked))
            val accept = AcceptContact(access)
            val stale = Observation(TrustConflict.KEY_CHANGED, "user1", mapOf(KeyBytes(ByteArray(32) { 8 }) to id(101)))
            assertEquals(AcceptResult.Changed, (accept.blindly(id(1), stale) as AcceptOutcome.Result).result)
            assertEquals(
                ContactStatus.BLOCKED,
                store.book
                    ?.contacts
                    ?.getValue(id(1))
                    ?.status,
            )
            assertEquals(AcceptResult.Accepted, (accept.blindly(id(1), checkNotNull(blocked.pending)) as AcceptOutcome.Result).result)
            assertEquals(
                ContactStatus.OK,
                store.book
                    ?.contacts
                    ?.getValue(id(1))
                    ?.status,
            )
            store.book = null
            assertEquals(AcceptOutcome.Unavailable, accept.blindly(id(1), stale))
        }
}
