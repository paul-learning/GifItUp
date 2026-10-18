package app.gifitup.shared.domain.auth

import app.gifitup.protocol.ProtocolJson
import app.gifitup.protocol.RegisterRequest
import app.gifitup.shared.data.crypto.FakeMlsClient
import app.gifitup.shared.data.network.ApiError
import app.gifitup.shared.data.network.FakeAuthServer
import app.gifitup.shared.data.network.FakeAuthServer.Companion.DEVICE_ID
import app.gifitup.shared.data.network.FakeAuthServer.Companion.dropped
import app.gifitup.shared.data.network.FakeAuthServer.Companion.error
import app.gifitup.shared.data.network.Session
import app.gifitup.shared.data.storage.StoredIdentity
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class RegisterDeviceTest {
    private val server = FakeAuthServer()
    private val identities = InMemoryIdentityStore()
    private val keys = InMemoryKeyStore()
    private val random = CountingRandom()
    private val waits = mutableListOf<Duration>()
    private val register = RegisterDevice(server.api(), unguarded(), identities, keys, random, DeviceLock()) { waits += it }

    private fun registerRequests() = server.bodiesFor("/accounts").map { ProtocolJson.decodeFromString(RegisterRequest.serializer(), it) }

    @Test
    fun registersAndStoresTheDeviceWithTheKeyItWasCreatedWith() =
        runTest {
            assertEquals(DEVICE_ID, assertIs<AuthResult.Ok<Session>>(register("ann", INVITE)).value.deviceId)

            assertEquals(listOf(32), random.sizes)
            val stored = identities.identity!!
            assertEquals(StoredIdentity(ORIGIN, "ann", stored.snapshot, DEVICE_ID).toString(), stored.toString())
            // The persisted key opens the persisted snapshot: it is the key the client was created with (#79 SEC-5).
            FakeMlsClient.Factory.restore(stored.snapshot, keys.key!!).use { assertEquals("ann", it.publicIdentity().username) }
            // The invite code is never stored.
            identities.saved.forEach { assertFalse(INVITE in it.toString() || INVITE in it.snapshot.decodeToString()) }
        }

    @Test
    fun theIdentityIsPersistedBeforeTheFirstRequest() =
        runTest {
            server.on("/accounts", dropped)
            assertEquals(AuthResult.Failed(AuthError.Api(ApiError.NETWORK)), register("ann", INVITE))
            val pending = identities.identity!!
            assertEquals("ann", pending.username)
            assertNull(pending.deviceId)
            assertEquals(1, keys.saves)
        }

    @Test
    fun aLostReplyIsResumedWithTheSameKeyAndUsernameAndAFreshChallenge() =
        runTest {
            server.on("/accounts", dropped)
            register("ann", INVITE)
            assertIs<AuthResult.Ok<*>>(register("ann", INVITE))

            val (first, second) = registerRequests()
            assertEquals(first.username, second.username)
            assertEquals(first.signaturePublicKey, second.signaturePublicKey)
            assertNotEquals(first.challenge, second.challenge)
            assertEquals(1, keys.saves)
            assertEquals(1, random.sizes.size)
            assertEquals(DEVICE_ID, identities.identity?.deviceId)
        }

    @Test
    fun aRefusalOnAResumeIsRetriedOnceAfterADelay() =
        runTest {
            server.on("/accounts", dropped, error(HttpStatusCode.Conflict, "username_unavailable"))
            register("ann", INVITE)
            assertIs<AuthResult.Ok<*>>(register("ann", INVITE))

            assertEquals(listOf(3.seconds), waits)
            assertEquals(3, registerRequests().map { it.challenge }.toSet().size)
            assertEquals(3, server.bodiesFor("/auth/challenge").size)
        }

    @Test
    fun aDefiniteRefusalOnAFirstAttemptDiscardsThePendingIdentity() =
        runTest {
            server.on("/accounts", error(HttpStatusCode.Conflict, "username_unavailable"))
            assertEquals(AuthResult.Failed(AuthError.Api(ApiError.USERNAME_UNAVAILABLE)), register("ann", INVITE))
            assertEquals(emptyList(), waits)
            assertNull(identities.identity)
            assertNull(keys.key)
            // So another username can be tried right away.
            assertIs<AuthResult.Ok<Session>>(register("bob", INVITE))
            assertEquals("bob", registerRequests().last().username)
        }

    @Test
    fun aRefusalOnAResumeKeepsTheIdentityUntilTheUserDiscardsIt() =
        runTest {
            val refused = error(HttpStatusCode.Forbidden, "invite_invalid")
            server.on("/accounts", dropped, refused, refused)
            register("ann", INVITE)
            // E.g. a resume with another, mistyped code: the lost attempt may still be committing (#95 CR-5, SEC-6).
            assertEquals(AuthResult.Failed(AuthError.Api(ApiError.INVITE_INVALID)), register("ann", "BAAAAAAAAAAAAAAAAAAAAA"))
            assertEquals(1, waits.size)
            assertEquals(3, registerRequests().size)
            assertEquals("ann", identities.identity?.username)
            assertEquals(1, keys.saves)

            assertIs<AuthResult.Ok<Unit>>(register.discardPending())
            assertNull(identities.identity)
            assertNull(keys.key)
            assertIs<AuthResult.Ok<Session>>(register("bob", INVITE))
            assertEquals(AuthResult.Failed(AuthError.Local.ALREADY_REGISTERED), register.discardPending())
            assertEquals("bob", identities.identity?.username)
        }

    @Test
    fun aBadRequestOnAResumeKeepsTheIdentityToo() =
        runTest {
            server.on("/accounts", dropped, error(HttpStatusCode.BadRequest, "bad_request"))
            register("ann", INVITE)
            assertEquals(AuthResult.Failed(AuthError.Api(ApiError.BAD_REQUEST)), register("ann", INVITE))
            assertEquals("ann", identities.identity?.username)
            assertEquals(emptyList(), waits)
        }

    @Test
    fun otherFailuresKeepThePendingIdentityForAResume() =
        runTest {
            val failures =
                listOf(
                    dropped,
                    error(HttpStatusCode.Unauthorized, "unauthorized"),
                    error(HttpStatusCode.TooManyRequests, "rate_limited"),
                    error(HttpStatusCode.InternalServerError, "internal"),
                )
            failures.forEach { reply ->
                server.on("/accounts", reply)
                assertIs<AuthResult.Failed>(register("ann", INVITE))
                assertEquals("ann", identities.identity?.username)
            }
            assertEquals(1, keys.saves)
        }

    @Test
    fun nothingIsSentWhenTheKeyOrTheIdentityCannotBeStored() =
        runTest {
            keys.failSave = true
            assertFailsWith<IllegalStateException> { register("ann", INVITE) }
            keys.failSave = false
            identities.failSave = true
            assertFailsWith<IllegalStateException> { register("ann", INVITE) }
            assertEquals(emptyList(), server.requests)
        }

    @Test
    fun aDiscardNeverInterleavesWithARegistration() =
        runTest {
            val (registered, discarded) =
                listOf(async { register("ann", INVITE) }, async { register.discardPending() }).awaitAll()
            assertIs<AuthResult.Ok<*>>(registered)
            assertEquals(AuthResult.Failed(AuthError.Local.ALREADY_REGISTERED), discarded)
            // The registered identity and its key survive, and still belong together.
            assertEquals(DEVICE_ID, identities.identity?.deviceId)
            FakeMlsClient.Factory.restore(identities.identity!!.snapshot, keys.key!!).close()
        }

    @Test
    fun concurrentCallsRunOneAfterTheOther() =
        runTest {
            val results = List(2) { async { register("ann", INVITE) } }.awaitAll()
            assertEquals(1, keys.saves)
            assertEquals(1, results.count { it is AuthResult.Ok })
            assertEquals(AuthResult.Failed(AuthError.Local.ALREADY_REGISTERED), results.single { it is AuthResult.Failed })
            FakeMlsClient.Factory.restore(identities.identity!!.snapshot, keys.key!!).close()
        }

    @Test
    fun localRefusalsSendNothing() =
        runTest {
            val cases =
                listOf(
                    Triple(null, "Ann", AuthError.Local.INVALID_USERNAME),
                    Triple(null, "an", AuthError.Local.INVALID_USERNAME),
                    Triple(StoredIdentity(ORIGIN, "ann", ByteArray(0), DEVICE_ID), "ann", AuthError.Local.ALREADY_REGISTERED),
                    Triple(StoredIdentity(ORIGIN, "bob", ByteArray(0), null), "ann", AuthError.Local.USERNAME_MISMATCH),
                    Triple(StoredIdentity("https://other.test", "ann", ByteArray(0), null), "ann", AuthError.Local.OTHER_ORIGIN),
                    Triple(StoredIdentity(ORIGIN, "ann", ByteArray(0), null), "ann", AuthError.Local.STATE_INVALID),
                )
            cases.forEach { (stored, username, expected) ->
                identities.identity = stored
                assertEquals(AuthResult.Failed(expected), register(username, INVITE), "$stored $username")
            }
            identities.identity = null
            listOf(INVITE.dropLast(1), INVITE + "A", INVITE.dropLast(1) + "B", INVITE.replace('A', '+')).forEach {
                assertEquals(AuthResult.Failed(AuthError.Local.INVALID_INVITE_CODE), register("ann", it), it)
            }
            assertEquals(emptyList(), server.requests)
            assertEquals(0, keys.saves)
        }

    @Test
    fun aDamagedPendingIdentityIsStateInvalid() =
        runTest {
            server.on("/accounts", dropped)
            register("ann", INVITE)
            keys.key = ByteArray(32) { 9 }
            assertEquals(AuthResult.Failed(AuthError.Local.STATE_INVALID), register("ann", INVITE))
        }

    private companion object {
        const val ORIGIN = FakeAuthServer.ORIGIN

        /** 16 bytes, canonical base64url (its last character carries no stray bits). */
        const val INVITE = "AAAAAAAAAAAAAAAAAAAAAA"
    }
}
