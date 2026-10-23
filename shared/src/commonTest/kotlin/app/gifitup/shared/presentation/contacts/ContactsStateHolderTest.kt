package app.gifitup.shared.presentation.contacts

import app.gifitup.shared.data.contacts.Contact
import app.gifitup.shared.data.contacts.ContactBook
import app.gifitup.shared.data.contacts.ContactStatus
import app.gifitup.shared.data.contacts.KeyBytes
import app.gifitup.shared.data.contacts.Observation
import app.gifitup.shared.data.contacts.Sighting
import app.gifitup.shared.data.contacts.TrustConflict
import app.gifitup.shared.data.contacts.Verdict
import app.gifitup.shared.data.crypto.FingerprintEmojis
import app.gifitup.shared.data.network.Base64Url
import app.gifitup.shared.data.network.FakeAuthServer
import app.gifitup.shared.data.network.FakeAuthServer.Companion.DEVICE_ID
import app.gifitup.shared.data.network.FakeAuthServer.Companion.json
import app.gifitup.shared.data.network.Session
import app.gifitup.shared.data.storage.InMemoryContactStore
import app.gifitup.shared.data.storage.StoredIdentity
import app.gifitup.shared.domain.auth.AuthResult
import app.gifitup.shared.domain.auth.CountingRandom
import app.gifitup.shared.domain.auth.DeviceLock
import app.gifitup.shared.domain.auth.InMemoryIdentityStore
import app.gifitup.shared.domain.auth.InMemoryKeyStore
import app.gifitup.shared.domain.auth.LogIn
import app.gifitup.shared.domain.auth.RegisterDevice
import app.gifitup.shared.domain.auth.unguarded
import app.gifitup.shared.domain.contacts.AcceptContact
import app.gifitup.shared.domain.contacts.ContactBookAccess
import app.gifitup.shared.domain.contacts.ContactsUseCases
import app.gifitup.shared.domain.contacts.RefreshContacts
import app.gifitup.shared.domain.contacts.ShowFingerprints
import app.gifitup.shared.domain.session.SessionHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Friends screen's logic (ADR-0014): saved keys only, a review bound to what it showed, no blind trust. */
class ContactsStateHolderTest {
    private val server = FakeAuthServer()
    private val identities = InMemoryIdentityStore()
    private val keys = InMemoryKeyStore()
    private val store = InMemoryContactStore()
    private val access = ContactBookAccess(store) { 1_000L }
    private val lock = DeviceLock()
    private val useCases =
        ContactsUseCases(
            RefreshContacts(server.api(), unguarded(), identities, keys, lock, access),
            access,
            ShowFingerprints(server.api().endpoint.origin, unguarded(), identities, keys, lock),
            AcceptContact(access),
        )

    private fun id(n: Int) = "00000000-0000-4000-8000-" + n.toString().padStart(12, '0')

    private fun key(n: Int) = KeyBytes(ByteArray(32) { (n * 16 + it).toByte() })

    private fun seen(
        account: Int,
        name: String,
        vararg keyNumbers: Int,
    ) = Sighting(id(account), name, keyNumbers.associate { key(it) to id(1000 + it) })

    private fun emojisOf(key: KeyBytes) = FingerprintEmojis.cells(key.bytes.copyOf(FingerprintEmojis.LENGTH))

    private fun members(
        vararg members: String,
        more: Boolean = false,
    ) = json("""{"members":[${members.joinToString(",")}],"more":$more}""")

    private fun member(
        account: Int,
        name: String,
        keyNumber: Int,
    ) = """{"accountId":"${id(account)}","username":"$name","relation":"invitee","devices":[{"deviceId":"${id(
        1000 + keyNumber,
    )}","signatureKey":"${Base64Url.encode(key(keyNumber).bytes)}"}]}"""

    private suspend fun registered(): Session {
        val api = server.api()
        RegisterDevice(api, unguarded(), identities, keys, CountingRandom(), DeviceLock())("ann", "AAAAAAAAAAAAAAAAAAAAAA")
        val stored = identities.identity!!
        identities.identity = StoredIdentity(stored.origin, stored.username, stored.snapshot, DEVICE_ID)
        val session = assertIs<AuthResult.Ok<Session>>(LogIn(api, unguarded(), identities, keys, DeviceLock())()).value
        identities.saved.clear()
        server.requests.clear()
        return session
    }

    private suspend fun TestScope.holder(): ContactsStateHolder {
        val session = registered()
        return ContactsStateHolder(this, SessionHolder({ AuthResult.Ok(session) }, this) { 0L }, useCases)
    }

    /** The requests run on the HTTP client's own threads: run the test scheduler, then wait in real time until the action is done. */
    private suspend fun TestScope.idle(holder: ContactsStateHolder) {
        advanceUntilIdle()
        withContext(Dispatchers.Default) { withTimeout(REAL_TIMEOUT_MS) { holder.state.first { !it.busy } } }
    }

    private fun bookWith(vararg contacts: Contact) = ContactBook(contacts.associateBy { it.accountId })

    private fun saved(
        account: Int,
        name: String,
        vararg keyNumbers: Int,
    ) = Contact(id(account), name, ContactStatus.OK, keyNumbers.associate { key(it) to id(1000 + it) }, null, 1L, 1L)

    /** A saved friend whose key changed: `bob` was pinned with key 1 and a claim showed key 2. */
    private fun blockedBob(): ContactBook {
        val (book, verdict) =
            bookWith(saved(2, "bob", 1)).claim(
                seen(2, "bob", 2).let {
                    Sighting(
                        it.accountId,
                        it.username,
                        mapOf(key(2) to id(1001)),
                    )
                },
                2_000L,
            )
        assertEquals(Verdict.Blocked(TrustConflict.KEY_CHANGED), verdict)
        return book
    }

    private suspend fun TestScope.opened(): ContactsStateHolder {
        val holder = holder()
        server.on("/members", members())
        holder.open()
        idle(holder)
        return holder
    }

    @Test
    fun showsSavedFriendsWithEmojisFromTheirPinsAndListedOnesWithNone() =
        runTest {
            store.book = bookWith(saved(2, "bob", 1))
            val holder = holder()
            server.on("/members", members(member(2, "bob", 1), member(3, "cara", 3)))
            holder.open()
            idle(holder)
            val state = holder.state.value
            assertFalse(state.busy)
            assertNotNull(state.own)
            val bob = state.friends.single { it.username == "bob" }
            assertEquals(FriendStatus.SAVED, bob.status)
            assertEquals(listOf(emojisOf(key(1))), bob.devices)
            val cara = state.friends.single { it.username == "cara" }
            assertEquals(FriendStatus.NOT_SAVED, cara.status)
            assertEquals(emptyList(), cara.devices, "a listed key says nothing about the key a claim will pin (SEC-42)")
        }

    @Test
    fun ownEmojisComeFromTheLocalKeyNotFromTheList() =
        runTest {
            val holder = holder()
            val own =
                unguarded()
                    .restore(
                        identities.identity!!.snapshot,
                        keys.load()!!,
                    ).use { it.fingerprint(it.publicIdentity().signaturePublicKey) }
            server.on("/members", members(member(3, "cara", 3)))
            holder.open()
            idle(holder)
            assertEquals(FingerprintEmojis.cells(own), holder.state.value.own)
        }

    @Test
    fun anUnusableBookShowsNothingAndIsAProblem() =
        runTest {
            store.book = null
            val holder = holder()
            holder.open()
            idle(holder)
            assertEquals(FriendsProblem.BOOK_UNAVAILABLE, holder.state.value.problem)
            assertEquals(emptyList(), holder.state.value.friends)
            assertEquals(emptyList(), server.requests.filter { it.path == "/members" }, "an unusable book makes no request")
        }

    @Test
    fun aFailedRefreshKeepsTheSavedList() =
        runTest {
            store.book = bookWith(saved(2, "bob", 1))
            val holder = holder()
            server.on("/members", FakeAuthServer.dropped)
            holder.open()
            idle(holder)
            assertEquals(FriendsProblem.OFFLINE, holder.state.value.problem)
            assertEquals(
                listOf("bob"),
                holder.state.value.friends
                    .map { it.username },
            )
        }

    @Test
    fun failingEmojisShowNoEmojisAndAllowNoReview() =
        runTest {
            store.book =
                blockedBob().let {
                    ContactBook(
                        it.contacts + (id(2) to it.contacts.getValue(id(2)).copy(devices = mapOf(KeyBytes(ByteArray(31)) to id(5)))),
                    )
                }
            val holder = holder()
            server.on("/members", members())
            holder.open()
            idle(holder)
            assertEquals(FriendsProblem.EMOJIS_FAILED, holder.state.value.problem)
            assertTrue(
                holder.state.value.friends
                    .all { it.devices.isEmpty() },
            )
            holder.review(id(2))
            idle(holder)
            assertNull(holder.state.value.review)
        }

    @Test
    fun aBlockedFriendShowsBothSetsAndTrustPinsExactlyTheShownObservation() =
        runTest {
            store.book = blockedBob()
            val holder = opened()
            assertEquals(
                FriendStatus.CHANGED,
                holder.state.value.friends
                    .single()
                    .status,
            )
            holder.review(id(2))
            idle(holder)
            val review = assertNotNull(holder.state.value.review)
            assertEquals(TrustConflict.KEY_CHANGED, review.reason)
            assertEquals(listOf(emojisOf(key(1))), review.pinned)
            assertEquals(listOf(emojisOf(key(2))), review.observed)
            assertEquals(listOf(emojisOf(key(2))), review.resulting)
            assertTrue(review.canTrust)

            holder.trust(confirmed = false)
            idle(holder)
            assertEquals(
                ContactStatus.BLOCKED,
                store.book
                    ?.contacts
                    ?.getValue(id(2))
                    ?.status,
                "no confirmation, no trust",
            )

            holder.trust(confirmed = true)
            idle(holder)
            assertNull(holder.state.value.review)
            assertEquals(
                setOf(key(2)),
                store.book
                    ?.contacts
                    ?.getValue(id(2))
                    ?.devices
                    ?.keys,
            )
            assertEquals(
                FriendStatus.SAVED,
                holder.state.value.friends
                    .single()
                    .status,
            )
            assertEquals(
                listOf(emojisOf(key(2))),
                holder.state.value.friends
                    .single()
                    .devices,
            )
        }

    @Test
    fun anObservationThatChangedWhileReviewingIsNeverPinnedAndTheConfirmationMustBeGivenAgain() =
        runTest {
            store.book = blockedBob()
            val holder = opened()
            holder.review(id(2))
            idle(holder)
            val first = assertNotNull(holder.state.value.review)

            // The server shows another key while the user is comparing.
            store.book = store.book!!.claim(Sighting(id(2), "bob", mapOf(key(3) to id(1001))), 3_000L).first
            holder.trust(confirmed = true)
            idle(holder)

            val second = assertNotNull(holder.state.value.review)
            assertEquals(
                setOf(key(1)),
                store.book
                    ?.contacts
                    ?.getValue(id(2))
                    ?.devices
                    ?.keys,
                "nothing was pinned",
            )
            assertEquals(1, second.changes)
            assertTrue(second.version > first.version, "the screen must reset its confirmation")
            assertEquals(listOf(emojisOf(key(3))), second.observed)
            assertTrue(second.canTrust)

            store.book = store.book!!.claim(Sighting(id(2), "bob", mapOf(key(4) to id(1001))), 4_000L).first
            holder.trust(confirmed = true)
            idle(holder)
            val third = assertNotNull(holder.state.value.review)
            assertEquals(2, third.changes)
            assertFalse(third.canTrust, "a key that keeps changing is not offered for trust")
            holder.trust(confirmed = true)
            idle(holder)
            assertEquals(
                setOf(key(1)),
                store.book
                    ?.contacts
                    ?.getValue(id(2))
                    ?.devices
                    ?.keys,
            )
        }

    @Test
    fun theChangeCountSurvivesCancelAndReopenAndChangesBetweenReviews() =
        runTest {
            store.book = blockedBob()
            val holder = opened()
            holder.review(id(2))
            idle(holder)
            assertEquals(0, assertNotNull(holder.state.value.review).changes)
            holder.cancelReview()

            // The server rotates the observation while no review is open; each review that sees a new one counts it.
            store.book = store.book!!.claim(Sighting(id(2), "bob", mapOf(key(3) to id(1001))), 3_000L).first
            holder.review(id(2))
            idle(holder)
            val second = assertNotNull(holder.state.value.review)
            assertEquals(1, second.changes)
            assertTrue(second.canTrust)
            holder.cancelReview()

            store.book = store.book!!.claim(Sighting(id(2), "bob", mapOf(key(4) to id(1001))), 4_000L).first
            holder.review(id(2))
            idle(holder)
            val third = assertNotNull(holder.state.value.review)
            assertEquals(2, third.changes)
            assertFalse(third.canTrust)

            holder.cancelReview()
            holder.review(id(2))
            idle(holder)
            assertEquals(2, assertNotNull(holder.state.value.review).changes, "cancel and reopen does not reset it")
            assertFalse(assertNotNull(holder.state.value.review).canTrust)
        }

    @Test
    fun aRefreshIsIgnoredWhileAReviewIsOpen() =
        runTest {
            store.book = blockedBob()
            val holder = opened()
            holder.review(id(2))
            idle(holder)
            val before = server.requests.size
            holder.refresh()
            idle(holder)
            assertEquals(before, server.requests.size)
            assertNotNull(holder.state.value.review)
            holder.cancelReview()
            assertNull(holder.state.value.review)
        }

    @Test
    fun aKeyConflictCanBeExplainedButNeverTrusted() =
        runTest {
            val pinned = bookWith(saved(2, "bob", 1), saved(3, "cara", 2))
            // The list shows bob (account 2) with cara's key: bob is blocked as a key conflict.
            store.book = pinned.list(listOf(seen(2, "bob", 2)), complete = false, 2_000L)
            val holder = opened()
            holder.review(id(2))
            idle(holder)
            val review = assertNotNull(holder.state.value.review)
            assertEquals(TrustConflict.KEY_CONFLICT, review.reason)
            assertFalse(review.canTrust)
            holder.trust(confirmed = true)
            idle(holder)
            assertEquals(
                ContactStatus.BLOCKED,
                store.book
                    ?.contacts
                    ?.getValue(id(2))
                    ?.status,
            )
        }

    @Test
    fun aReassignedNameShowsTheNewNameAndTheReason() =
        runTest {
            val (book, verdict) = bookWith(saved(2, "bob", 1)).claim(seen(5, "bob", 5), 2_000L)
            assertEquals(Verdict.Blocked(TrustConflict.USERNAME_REASSIGNED), verdict)
            store.book = book
            val holder = opened()
            assertEquals(
                setOf(FriendStatus.GONE, FriendStatus.CHANGED),
                holder.state.value.friends
                    .map { it.status }
                    .toSet(),
            )
            holder.review(id(5))
            idle(holder)
            val review = assertNotNull(holder.state.value.review)
            assertEquals(TrustConflict.USERNAME_REASSIGNED, review.reason)
            assertEquals(listOf(emojisOf(key(5))), review.observed)
            assertTrue(review.pinned.isEmpty(), "the new account has no saved key yet")
        }

    @Test
    fun leavingDropsEverythingAndALateAnswerIsIgnored() =
        runTest {
            store.book = blockedBob()
            val holder = holder()
            server.on("/members", members())
            holder.open()
            holder.leave()
            idle(holder)
            assertEquals(FriendsState(), holder.state.value)

            val again = opened()
            again.review(id(2))
            idle(again)
            assertNotNull(again.state.value.review)
            again.leave()
            assertEquals(FriendsState(), again.state.value)
            assertTrue(again.state.value.own == null && again.state.value.review == null)
        }

    @Test
    fun trustWithoutAnOpenReviewDoesNothing() =
        runTest {
            store.book = blockedBob()
            val holder = opened()
            holder.trust(confirmed = true)
            idle(holder)
            assertEquals(
                ContactStatus.BLOCKED,
                store.book
                    ?.contacts
                    ?.getValue(id(2))
                    ?.status,
            )
        }

    @Test
    fun thePendingObservationOfTheBookIsWhatTheReviewHolds() =
        runTest {
            store.book = blockedBob()
            val holder = opened()
            holder.review(id(2))
            idle(holder)
            val pending: Observation =
                checkNotNull(
                    store.book
                        ?.contacts
                        ?.getValue(id(2))
                        ?.pending,
                )
            assertEquals(
                pending.username,
                holder.state.value.review
                    ?.observedUsername,
            )
        }
}

private const val REAL_TIMEOUT_MS = 20_000L
