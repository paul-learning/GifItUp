package app.gifitup.shared.domain.auth

import app.gifitup.protocol.LoginRequest
import app.gifitup.protocol.ProtocolJson
import app.gifitup.shared.data.crypto.FakeMlsClient
import app.gifitup.shared.data.network.ApiError
import app.gifitup.shared.data.network.Base64Url
import app.gifitup.shared.data.network.Device
import app.gifitup.shared.data.network.FakeAuthServer
import app.gifitup.shared.data.network.FakeAuthServer.Companion.DEVICE_ID
import app.gifitup.shared.data.network.FakeAuthServer.Companion.json
import app.gifitup.shared.data.network.Session
import app.gifitup.shared.data.storage.StoredIdentity
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class LogInTest {
    private val server = FakeAuthServer()
    private val identities = InMemoryIdentityStore()
    private val keys = InMemoryKeyStore()
    private val logIn = LogIn(server.api(), unguarded(), identities, keys, DeviceLock())

    /** A registered identity, made the way [RegisterDevice] makes one. */
    private suspend fun registered(deviceId: String? = DEVICE_ID) {
        identities.identity = null
        keys.key = null
        RegisterDevice(server.api(), unguarded(), identities, keys, CountingRandom(), DeviceLock())("ann", "AAAAAAAAAAAAAAAAAAAAAA")
        identities.identity = identities.identity!!.let { StoredIdentity(it.origin, it.username, it.snapshot, deviceId) }
        server.requests.clear()
    }

    @Test
    fun signsAFreshChallengeForTheConfiguredOriginAndThisDevice() =
        runTest {
            registered()
            assertEquals(DEVICE_ID, assertIs<AuthResult.Ok<Session>>(logIn()).value.deviceId)
            assertIs<AuthResult.Ok<Session>>(logIn())

            val requests = server.bodiesFor("/auth/login").map { ProtocolJson.decodeFromString(LoginRequest.serializer(), it) }
            assertNotEquals(requests[0].challenge, requests[1].challenge)
            requests.forEach {
                assertEquals(DEVICE_ID, it.deviceId)
                // The fake's signature names what it signed: purpose, origin and subject.
                val signed = Base64Url.decode(it.signature, 1..512)!!.decodeToString()
                assertTrue(signed.startsWith("sig:Login:") && ":${FakeAuthServer.ORIGIN}:$DEVICE_ID:ann" in signed, signed)
            }
        }

    @Test
    fun refusesWithoutAUsableRegisteredIdentity() =
        runTest {
            assertEquals(AuthResult.Failed(AuthError.Local.NOT_REGISTERED), logIn())
            registered(deviceId = null)
            assertEquals(AuthResult.Failed(AuthError.Local.NOT_REGISTERED), logIn())
            registered()
            val other = identities.identity!!.let { StoredIdentity("https://other.test", it.username, it.snapshot, it.deviceId) }
            identities.identity = other
            assertEquals(AuthResult.Failed(AuthError.Local.OTHER_ORIGIN), logIn())
            registered()
            keys.key = ByteArray(32) { 9 }
            assertEquals(AuthResult.Failed(AuthError.Local.STATE_INVALID), logIn())
            keys.key = null
            assertEquals(AuthResult.Failed(AuthError.Local.STATE_INVALID), logIn())
            assertEquals(emptyList(), server.requests)
        }

    @Test
    fun aSessionForAnotherDeviceIsOutsideTheProtocol() =
        runTest {
            registered()
            server.on("/auth/login", json(FakeAuthServer.session("11111111-2222-4333-8444-555555555555")))
            assertEquals(AuthResult.Failed(AuthError.Api(ApiError.MALFORMED_RESPONSE)), logIn())
        }

    @Test
    fun listsDevicesAndLogsOutWithTheSession() =
        runTest {
            registered()
            val session = assertIs<AuthResult.Ok<Session>>(logIn()).value
            assertEquals(listOf(Device(DEVICE_ID, 1, 2)), assertIs<AuthResult.Ok<List<Device>>>(ListDevices(server.api())(session)).value)
            assertIs<AuthResult.Ok<Unit>>(LogOut(server.api())(session))
            assertEquals(listOf("/auth/challenge", "/auth/login", "/devices", "/auth/logout"), server.requests.map { it.path })
        }
}
