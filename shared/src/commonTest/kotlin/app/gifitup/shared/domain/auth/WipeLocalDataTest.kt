package app.gifitup.shared.domain.auth

import app.gifitup.shared.data.crypto.FakeHistoryCipher
import app.gifitup.shared.data.crypto.FakeMlsClient
import app.gifitup.shared.data.crypto.MlsErrorCode
import app.gifitup.shared.data.crypto.MlsException
import app.gifitup.shared.data.network.FakeAuthServer
import app.gifitup.shared.data.network.FakeAuthServer.Companion.DEVICE_ID
import app.gifitup.shared.data.network.Session
import app.gifitup.shared.data.storage.InMemoryRecordStore
import app.gifitup.shared.domain.contacts.KeyBytes
import app.gifitup.shared.domain.messages.EncryptedMessageStore
import app.gifitup.shared.domain.messages.HistoryStoreException
import app.gifitup.shared.domain.messages.LazyHistory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
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
            WipeLocalData(data, LogOut(server.api()), DeviceLock(), HistoryCloser.None)(session, clients)
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
            WipeLocalData(
                data,
                LogOut(server.api()),
                DeviceLock(),
                HistoryCloser.None,
            )(Session(DEVICE_ID, "token", 1, FakeAuthServer.ENDPOINT), emptyList())
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
            val wipe = WipeLocalData(flaky, LogOut(server.api()), DeviceLock(), HistoryCloser.None)
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
            val register =
                async {
                    RegisterDevice(
                        server.api(),
                        unguarded(),
                        identities,
                        keys,
                        CountingRandom(),
                        lock,
                        HistoryLeftovers.None,
                    )("ann", INVITE)
                }
            val wipe = async { WipeLocalData(wipeAll, LogOut(server.api()), lock, HistoryCloser.None)(null, emptyList()) }
            awaitAll(register, wipe)
            assertEquals(listOf("destroy key after 2 saves"), steps)
            assertEquals(null, identities.identity)
        }

    @Test
    fun withoutASessionNothingIsSent() =
        runTest {
            WipeLocalData(data, LogOut(server.api()), DeviceLock(), HistoryCloser.None)(null, emptyList())
            assertEquals(emptyList(), server.requests)
            assertEquals(listOf("destroy key", "delete records"), steps)
        }

    /** The history is closed first of all, outside the device lock, and before the key is destroyed (PR 208 CR-1). */
    @Test
    fun theHistoryIsClosedFirstAndBeforeTheKeyIsDestroyed() =
        runTest {
            val order = mutableListOf<String>()
            val closing = HistoryCloser { order += "history closed" }
            val recording =
                object : DeviceData {
                    override suspend fun destroyWrappingKey() {
                        order += "destroy key"
                    }

                    override suspend fun deleteAll() {
                        order += "delete records"
                    }
                }
            WipeLocalData(recording, LogOut(server.api()), DeviceLock(), closing)(null, emptyList())
            assertEquals(listOf("history closed", "destroy key", "delete records"), order)
        }

    /**
     * The wipe closes the history **before** it takes the device lock: a history that is still being opened may need the
     * device lock itself (here simulated), and waiting for it while holding the lock would hang the wipe (PR 208 CR-1).
     */
    @Test
    fun aWipeRacingTheFirstOpenOfTheHistoryDoesNotDeadlock() =
        runTest {
            val lock = DeviceLock()
            val go = CompletableDeferred<Unit>()
            val history =
                LazyHistory({
                    go.await()
                    lock.holding { }
                    EncryptedMessageStore(FakeHistoryCipher(), InMemoryRecordStore())
                }, { 0L })
            val first = async { runCatching { history.messages(KeyBytes(ByteArray(16))) } }
            runCurrent()
            val wipe = async { WipeLocalData(data, LogOut(server.api()), lock, { history.close() })(null, emptyList()) }
            runCurrent()
            go.complete(Unit)
            advanceUntilIdle()
            assertEquals(true, wipe.isCompleted, "the wipe finished")
            assertEquals(listOf("destroy key", "delete records"), steps)
            assertEquals(emptyList(), first.await().getOrNull(), "the open that was running finished before the close")
            assertFailsWith<HistoryStoreException> { history.messages(KeyBytes(ByteArray(16))) }
        }
}
