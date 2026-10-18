package app.gifitup.shared.domain.auth

import app.gifitup.shared.data.crypto.FakeMlsClient
import app.gifitup.shared.data.crypto.MlsErrorCode
import app.gifitup.shared.data.crypto.MlsException
import app.gifitup.shared.data.network.FakeAuthServer
import app.gifitup.shared.data.network.FakeAuthServer.Companion.DEVICE_ID
import app.gifitup.shared.data.network.Session
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WipeLocalDataTest {
    private val server = FakeAuthServer()
    private val steps = mutableListOf<String>()

    private val data =
        object : DeviceData {
            override suspend fun destroyWrappingKey() {
                steps += "destroy key"
            }

            override suspend fun deleteAll() {
                steps += "delete records"
            }
        }

    /** Log out, close the clients, destroy the key, then delete the records (#16 SEC-3, SEC-8). */
    @Test
    fun wipesInOrderAndClosesEveryClient() =
        runTest {
            val clients = List(2) { FakeMlsClient.Factory.create("ann", ByteArray(32)) }
            val session = Session(DEVICE_ID, "token", 1, FakeAuthServer.ENDPOINT)
            WipeLocalData(data, LogOut(server.api()))(session, clients)
            assertEquals(listOf("/auth/logout"), server.requests.map { it.path })
            assertEquals(listOf("destroy key", "delete records"), steps)
            clients.forEach { client ->
                assertEquals(MlsErrorCode.Poisoned, assertFailsWith<MlsException> { client.publicIdentity() }.code)
            }
        }

    @Test
    fun aFailedLogoutDoesntStopTheWipe() =
        runTest {
            server.on("/auth/logout", FakeAuthServer.dropped)
            WipeLocalData(data, LogOut(server.api()))(Session(DEVICE_ID, "token", 1, FakeAuthServer.ENDPOINT), emptyList())
            assertEquals(listOf("destroy key", "delete records"), steps)
        }

    @Test
    fun withoutASessionNothingIsSent() =
        runTest {
            WipeLocalData(data, LogOut(server.api()))(null, emptyList())
            assertEquals(emptyList(), server.requests)
            assertEquals(listOf("destroy key", "delete records"), steps)
        }
}
