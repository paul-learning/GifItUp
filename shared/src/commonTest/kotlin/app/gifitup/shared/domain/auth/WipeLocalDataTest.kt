package app.gifitup.shared.domain.auth

import app.gifitup.shared.data.crypto.FakeMlsClient
import app.gifitup.shared.data.crypto.MlsErrorCode
import app.gifitup.shared.data.crypto.MlsException
import app.gifitup.shared.data.network.FakeAuthServer
import app.gifitup.shared.data.network.FakeAuthServer.Companion.DEVICE_ID
import app.gifitup.shared.data.network.Session
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

private const val INVITE = "AAAAAAAAAAAAAAAAAAAAAA"

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
            WipeLocalData(data, LogOut(server.api()), DeviceLock())(session, clients)
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
            WipeLocalData(data, LogOut(server.api()), DeviceLock())(Session(DEVICE_ID, "token", 1, FakeAuthServer.ENDPOINT), emptyList())
            assertEquals(listOf("destroy key", "delete records"), steps)
        }

    /** A wipe that stopped part-way completes when run again (#108 SEC-18). */
    @Test
    fun aRetriedWipeCompletes() =
        runTest {
            var failOnce = true
            val flaky =
                object : DeviceData {
                    override suspend fun destroyWrappingKey() {
                        steps += "destroy key"
                    }

                    override suspend fun deleteAll() {
                        if (failOnce) {
                            failOnce = false
                            error("blocked")
                        }
                        steps += "delete records"
                    }
                }
            val wipe = WipeLocalData(flaky, LogOut(server.api()), DeviceLock())
            assertFailsWith<IllegalStateException> { wipe(null, emptyList()) }
            wipe(null, emptyList())
            assertEquals(listOf("destroy key", "destroy key", "delete records"), steps)
        }

    /** The wipe waits for a registration in flight instead of deleting what it is saving (#108 SEC-15). */
    @Test
    fun aWipeWaitsForARegistrationInFlight() =
        runTest {
            val lock = DeviceLock()
            val identities = InMemoryIdentityStore()
            val keys = InMemoryKeyStore()
            val wipeAll =
                object : DeviceData {
                    override suspend fun destroyWrappingKey() {
                        steps += "destroy key after ${identities.saved.size} saves"
                    }

                    override suspend fun deleteAll() {
                        identities.clear()
                        keys.clear()
                    }
                }
            val register = async { RegisterDevice(server.api(), unguarded(), identities, keys, CountingRandom(), lock)("ann", INVITE) }
            val wipe = async { WipeLocalData(wipeAll, LogOut(server.api()), lock)(null, emptyList()) }
            awaitAll(register, wipe)
            assertEquals(listOf("destroy key after 2 saves"), steps)
            assertEquals(null, identities.identity)
        }

    @Test
    fun withoutASessionNothingIsSent() =
        runTest {
            WipeLocalData(data, LogOut(server.api()), DeviceLock())(null, emptyList())
            assertEquals(emptyList(), server.requests)
            assertEquals(listOf("destroy key", "delete records"), steps)
        }

    /** The history is closed after the clients and before the key is destroyed, so nothing is written or kept afterwards. */
    @Test
    fun theHistoryIsClosedAfterTheClientsAndBeforeTheKeyIsDestroyed() =
        runTest {
            val order = mutableListOf<String>()
            val client = FakeMlsClient.Factory.create("ann", ByteArray(32))
            val closing =
                HistoryCloser {
                    order +=
                        "history closed after client poisoned=${runCatching { client.publicIdentity() }.isFailure}"
                }
            val recording =
                object : DeviceData {
                    override suspend fun destroyWrappingKey() {
                        order += "destroy key"
                    }

                    override suspend fun deleteAll() {
                        order += "delete records"
                    }
                }
            WipeLocalData(recording, LogOut(server.api()), DeviceLock(), closing)(null, listOf(client))
            assertEquals(listOf("history closed after client poisoned=true", "destroy key", "delete records"), order)
        }
}
