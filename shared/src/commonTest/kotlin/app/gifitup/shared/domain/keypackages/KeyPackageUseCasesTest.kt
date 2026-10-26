package app.gifitup.shared.domain.keypackages

import app.gifitup.protocol.ProtocolJson
import app.gifitup.protocol.UploadKeyPackagesRequest
import app.gifitup.shared.data.crypto.FakeMlsClient
import app.gifitup.shared.data.network.ApiError
import app.gifitup.shared.data.network.Base64Url
import app.gifitup.shared.data.network.FakeAuthServer
import app.gifitup.shared.data.network.FakeAuthServer.Companion.DEVICE_ID
import app.gifitup.shared.data.network.FakeAuthServer.Companion.error
import app.gifitup.shared.data.network.FakeAuthServer.Companion.json
import app.gifitup.shared.data.network.KeyPackageStatus
import app.gifitup.shared.data.network.Reply
import app.gifitup.shared.data.network.Session
import app.gifitup.shared.data.storage.InMemoryContactStore
import app.gifitup.shared.data.storage.StoredIdentity
import app.gifitup.shared.domain.auth.AuthError
import app.gifitup.shared.domain.auth.AuthResult
import app.gifitup.shared.domain.auth.CountingRandom
import app.gifitup.shared.domain.auth.DeviceLock
import app.gifitup.shared.domain.auth.InMemoryIdentityStore
import app.gifitup.shared.domain.auth.InMemoryKeyStore
import app.gifitup.shared.domain.auth.LogIn
import app.gifitup.shared.domain.auth.RegisterDevice
import app.gifitup.shared.domain.auth.unguarded
import app.gifitup.shared.domain.contacts.ContactBookAccess
import app.gifitup.shared.domain.contacts.ContactStatus
import app.gifitup.shared.domain.contacts.TrustConflict
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class KeyPackageUseCasesTest {
    private val server = FakeAuthServer()
    private val identities = InMemoryIdentityStore()
    private val keys = InMemoryKeyStore()
    private val lock = DeviceLock()
    private val refill = RefillKeyPackages(server.api(), unguarded(), identities, keys, lock)
    private val contactStore = InMemoryContactStore()
    private val access = ContactBookAccess(contactStore) { 1_000L }
    private val claim = ClaimKeyPackages(server.api(), unguarded(), identities, keys, lock, access)

    /** A registered, logged-in device "ann". */
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

    private fun status(
        available: Int,
        low: Int = 20,
    ) = json("""{"available":$available,"lowWaterMark":$low}""")

    private fun uploaded(
        accepted: Int,
        available: Int,
    ) = json("""{"accepted":$accepted,"available":$available,"lowWaterMark":20}""")

    @Test
    fun belowTheMarkItCreatesAFullBatchPersistsThenUploads() =
        runTest {
            val session = registered()
            val snapshotsAtUpload = mutableListOf<Int>()
            server.on("/key-packages/status", status(available = 5))
            server.on(
                "/key-packages",
                {
                    snapshotsAtUpload += identities.saved.size
                    uploaded(50, 55)(it)
                },
            )
            val done = assertIs<AuthResult.Ok<Refilled>>(refill(session)).value
            assertEquals(Refilled(50, 55), done)
            assertEquals(listOf(1), snapshotsAtUpload, "the exported state was saved before the upload went out")
            assertTrue(
                identities.identity!!
                    .snapshot
                    .decodeToString()
                    .endsWith(":50"),
                "the new private keys are in the stored state",
            )
            assertEquals(DEVICE_ID, identities.identity!!.deviceId)
            val sent = ProtocolJson.decodeFromString(UploadKeyPackagesRequest.serializer(), server.bodiesFor("/key-packages").single())
            assertEquals(50, sent.keyPackages.size)
        }

    @Test
    fun aFailedUploadKeepsThePersistedKeys() =
        runTest {
            val session = registered()
            server.on("/key-packages/status", status(available = 0))
            server.on("/key-packages", error(HttpStatusCode.InternalServerError, "internal"))
            assertEquals(AuthResult.Failed(AuthError.Api(ApiError.SERVER_ERROR)), refill(session))
            assertTrue(
                identities.identity!!
                    .snapshot
                    .decodeToString()
                    .endsWith(":50"),
            )
            assertEquals(1, identities.saved.size)
        }

    @Test
    fun atOrAboveTheMarkNothingIsCreatedOrSaved() =
        runTest {
            val session = registered()
            server.on("/key-packages/status", status(available = 20), status(available = 150))
            assertEquals(AuthResult.Ok(Refilled(0, 20)).value, assertIs<AuthResult.Ok<Refilled>>(refill(session)).value)
            assertEquals(Refilled(0, 150), assertIs<AuthResult.Ok<Refilled>>(refill(session)).value)
            assertEquals(emptyList(), identities.saved)
            assertEquals(emptyList(), server.bodiesFor("/key-packages"))
        }

    @Test
    fun theServersMarkIsClampedAndNeverExceedsTheCap() =
        runTest {
            val session = registered()
            // Mark 0 would never refill: it counts as 1, so an empty pool still fills.
            server.on("/key-packages/status", status(available = 0, low = 0), status(available = 1, low = 0))
            server.on("/key-packages", uploaded(50, 50))
            assertEquals(Refilled(50, 50), assertIs<AuthResult.Ok<Refilled>>(refill(session)).value)
            assertEquals(Refilled(0, 1), assertIs<AuthResult.Ok<Refilled>>(refill(session)).value, "1 is not below a mark of 1")

            // A huge mark counts as 200, and the batch never pushes past 200: 190 available -> 10 created.
            server.on("/key-packages/status", status(available = 190, low = Int.MAX_VALUE))
            server.on("/key-packages", uploaded(10, 200))
            assertEquals(Refilled(10, 200), assertIs<AuthResult.Ok<Refilled>>(refill(session)).value)
            val last = ProtocolJson.decodeFromString(UploadKeyPackagesRequest.serializer(), server.bodiesFor("/key-packages").last())
            assertEquals(10, last.keyPackages.size)
        }

    @Test
    fun checkReportsTheServersCounts() =
        runTest {
            val session = registered()
            server.on("/key-packages/status", status(available = 7, low = 20), error(HttpStatusCode.TooManyRequests, "rate_limited"))
            val check = CheckKeyPackages(server.api())
            assertEquals(7, assertIs<AuthResult.Ok<KeyPackageStatus>>(check(session)).value.available)
            assertEquals(AuthResult.Failed(AuthError.Api(ApiError.RATE_LIMITED)), check(session))
        }

    @Test
    fun refillNeedsARegisteredIdentity() =
        runTest {
            val session = registered()
            identities.identity = null
            assertEquals(AuthResult.Failed(AuthError.Local.NOT_REGISTERED), refill(session))
            assertEquals(emptyList(), server.requests)
        }

    @Test
    fun claimClassifiesUsableEmptyAndRefusedKeyPackages() =
        runTest {
            val session = registered()
            val good = "kp:ben:abc:0".encodeToByteArray()
            val wrongName = "kp:eve:abc:0".encodeToByteArray()
            val garbage = "nonsense".encodeToByteArray()
            val ids = List(4) { "00000000-0000-4000-8000-00000000000$it" }

            fun entry(
                id: String,
                bytes: ByteArray?,
            ) = """{"deviceId":"$id","keyPackage":${bytes?.let { "\"${Base64Url.encode(it)}\"" }}}"""
            server.on(
                "/key-packages/claim",
                json(
                    """{"accountId":"aaaaaaaa-2222-4333-8444-555555555555","devices":[${entry(
                        ids[0],
                        good,
                    )},${entry(ids[1], null)},${entry(ids[2], wrongName)},${entry(ids[3], garbage)}]}""",
                ),
            )
            val result = assertIs<AuthResult.Ok<List<ClaimedDeviceKey>>>(claim(session, "ben")).value
            val usable = assertIs<ClaimedDeviceKey.Usable>(result[0])
            assertContentEquals(good, usable.keyPackage)
            assertEquals("ben", usable.identity.username)
            assertEquals(ids[0], usable.deviceId)
            assertIs<ClaimedDeviceKey.Empty>(result[1])
            assertIs<ClaimedDeviceKey.Unusable>(result[2], "a KeyPackage for another username is dropped")
            assertIs<ClaimedDeviceKey.Unusable>(result[3], "so is junk")
            assertNotEquals(0, result.size)
        }

    @Test
    fun aClaimIsOneRequestAndNeverRepeatedOnFailure() =
        runTest {
            val session = registered()
            server.on(
                "/key-packages/claim",
                error(HttpStatusCode.TooManyRequests, "rate_limited"),
                error(HttpStatusCode.NotFound, "not_found"),
            )
            assertEquals(AuthResult.Failed(AuthError.Api(ApiError.RATE_LIMITED)), claim(session, "ben"))
            assertEquals(1, server.bodiesFor("/key-packages/claim").size)
            assertEquals(AuthResult.Failed(AuthError.Api(ApiError.NOT_FOUND)), claim(session, "ben"))
            assertEquals(2, server.bodiesFor("/key-packages/claim").size)
        }

    @Test
    fun anInvalidUsernameCostsNoRequestAndNoRestore() =
        runTest {
            val session = registered()
            assertEquals(AuthResult.Failed(AuthError.Local.INVALID_USERNAME), claim(session, "Ben!"))
            assertEquals(emptyList(), server.requests)
        }

    @Test
    fun claimNeedsARegisteredIdentity() =
        runTest {
            val session = registered()
            identities.identity = null
            assertEquals(AuthResult.Failed(AuthError.Local.NOT_REGISTERED), claim(session, "ben"))
            assertEquals(emptyList(), server.requests)
        }

    @Test
    fun theFakeCreatedTheKeyPackagesItHolds() {
        // Guards the test double the assertions above rely on: a created KeyPackage names its owner.
        val client = FakeMlsClient.Factory.create("ann", ByteArray(32) { 1 })
        val first =
            client
                .createKeyPackages(1)
                .single()
                .keyPackage
                .decodeToString()
        assertTrue(first.startsWith("kp:ann:"))
    }

    private val benAccount = "aaaaaaaa-2222-4333-8444-555555555555"
    private val device1 = "00000000-0000-4000-8000-000000000001"
    private val device2 = "00000000-0000-4000-8000-000000000002"

    private fun kp(tag: String) = "kp:ben:$tag:0".encodeToByteArray()

    private fun entry(
        id: String,
        bytes: ByteArray?,
    ) = """{"deviceId":"$id","keyPackage":${bytes?.let { "\"${Base64Url.encode(it)}\"" }}}"""

    private fun answer(
        account: String,
        vararg entries: String,
    ) = json("""{"accountId":"$account","devices":[${entries.joinToString(",")}]}""")

    @Test
    fun aFirstClaimPinsTheKeyAndALaterOneWithTheSameKeyPasses() =
        runTest {
            val session = registered()
            server.on("/key-packages/claim", answer(benAccount, entry(device1, kp("abc"))))
            assertIs<AuthResult.Ok<List<ClaimedDeviceKey>>>(claim(session, "ben"))
            assertEquals(1, contactStore.book?.pinCount)
            // Another KeyPackage with the same signature key (the fake keys it by name and tag).
            server.on("/key-packages/claim", answer(benAccount, entry(device1, kp("abc"))))
            assertIs<AuthResult.Ok<List<ClaimedDeviceKey>>>(claim(session, "ben"))
        }

    @Test
    fun aKeyThatDiffersFromThePinBlocksTheClaimAndHandsOnNothing() =
        runTest {
            val session = registered()
            server.on("/key-packages/claim", answer(benAccount, entry(device1, kp("abc"))), answer(benAccount, entry(device1, kp("xyz"))))
            claim(session, "ben")
            assertEquals(AuthResult.Failed(AuthError.Local.CONTACT_BLOCKED), claim(session, "ben"))
            val ben = checkNotNull(contactStore.book).contacts.getValue(benAccount)
            assertEquals(ContactStatus.BLOCKED, ben.status)
            assertEquals(TrustConflict.KEY_CHANGED, ben.pending?.reason)
        }

    @Test
    fun aBlockedContactIsRefusedBeforeTheRequestSoNoKeyPackageIsBurned() =
        runTest {
            val session = registered()
            server.on("/key-packages/claim", answer(benAccount, entry(device1, kp("abc"))), answer(benAccount, entry(device1, kp("xyz"))))
            claim(session, "ben")
            claim(session, "ben")
            val before = server.bodiesFor("/key-packages/claim").size
            assertEquals(AuthResult.Failed(AuthError.Local.CONTACT_BLOCKED), claim(session, "ben"))
            assertEquals(before, server.bodiesFor("/key-packages/claim").size, "no request")
        }

    @Test
    fun anUnusableBookRefusesBeforeTheRequest() =
        runTest {
            val session = registered()
            contactStore.book = null
            assertEquals(AuthResult.Failed(AuthError.Local.CONTACTS_UNAVAILABLE), claim(session, "ben"))
            assertEquals(emptyList(), server.requests)
        }

    @Test
    fun aPinThatCannotBeWrittenHandsOnNothing() =
        runTest {
            val session = registered()
            contactStore.failSaves = true
            server.on("/key-packages/claim", answer(benAccount, entry(device1, kp("abc"))))
            assertEquals(AuthResult.Failed(AuthError.Local.CONTACTS_UNAVAILABLE), claim(session, "ben"))
            assertEquals(0, contactStore.book?.pinCount)
        }

    @Test
    fun aFirstClaimWithTwoUsableDevicesIsBlockedAsAWholeAndPinsNothing() =
        runTest {
            val session = registered()
            server.on("/key-packages/claim", answer(benAccount, entry(device1, kp("abc")), entry(device2, kp("def"))))
            assertEquals(AuthResult.Failed(AuthError.Local.CONTACT_BLOCKED), claim(session, "ben"))
            val ben = checkNotNull(contactStore.book).contacts.getValue(benAccount)
            assertEquals(TrustConflict.MULTIPLE_DEVICES, ben.pending?.reason)
            assertEquals(emptyMap(), ben.devices)
        }

    @Test
    fun theSameKeyOnTwoDevicesOfOneClaimIsRefused() =
        runTest {
            val session = registered()
            server.on("/key-packages/claim", answer(benAccount, entry(device1, kp("abc")), entry(device2, kp("abc"))))
            assertEquals(AuthResult.Failed(AuthError.Api(ApiError.MALFORMED_RESPONSE)), claim(session, "ben"))
            assertEquals(0, contactStore.book?.pinCount)
        }

    @Test
    fun theBookIsNotLockedWhileTheRequestRuns() =
        runTest {
            val session = registered()
            val reply: Reply = { _ ->
                access.update { book, _ -> book to Unit } // would never return if the claim held the lock across its request
                respond(
                    """{"accountId":"$benAccount","devices":[${entry(device1, kp("abc"))}]}""",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
            server.on("/key-packages/claim", reply)
            assertIs<AuthResult.Ok<List<ClaimedDeviceKey>>>(claim(session, "ben"))
        }
}
