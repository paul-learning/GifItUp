package app.gifitup.shared.domain.auth

import app.gifitup.shared.data.crypto.FakeMlsClient
import app.gifitup.shared.data.network.FakeAuthServer
import app.gifitup.shared.data.network.FakeAuthServer.Companion.DEVICE_ID
import app.gifitup.shared.data.network.Session
import app.gifitup.shared.data.storage.InMemoryLocalStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/** Every use case of every [DeviceAuth] over one [DeviceStorage] holds the storage's one [DeviceLock] (#108 SEC-22). */
class DeviceAuthTest {
    private val server = FakeAuthServer()
    private val reads = mutableListOf<String>()

    /** Records reads too: login, registration and discard read the identity first. */
    private val store =
        object : InMemoryLocalStore() {
            override suspend fun get(key: String): ByteArray? {
                reads += key
                return super.get(key)
            }
        }
    private val wiped = mutableListOf<String>()
    private val data =
        object : DeviceData {
            override suspend fun destroyWrappingKey() {
                wiped += "key"
            }

            override suspend fun deleteAll() {
                wiped += "records"
            }
        }
    private val storage = DeviceStorage(store, data) { _, _ -> InMemoryKeyStore() }

    private fun auth() = DeviceAuth(storage, server.api(), FakeMlsClient.Factory, CountingRandom())

    @Test
    fun noUseCaseRunsWhileTheStoragesLockIsHeld() =
        runTest {
            // Two DeviceAuth instances, as two screens might build them: they still share the lock.
            val first = auth()
            val second = auth()
            val session = Session(DEVICE_ID, "token", 1, FakeAuthServer.ENDPOINT)
            val jobs =
                storage.lock.holding {
                    val jobs =
                        listOf(
                            launch { first.register("ann", INVITE) },
                            launch { second.register.discardPending() },
                            launch { first.logIn() },
                            launch { second.wipe(session, emptyList()) },
                        )
                    testScheduler.advanceUntilIdle()
                    // The fake server's engine runs outside the test scheduler: give a use case that got past the
                    // lock (a wipe logs out first) real time to reach it.
                    withContext(Dispatchers.Default) { delay(ENGINE_GRACE) }
                    testScheduler.advanceUntilIdle()
                    assertTrue(reads.isEmpty(), "no read while the lock is held")
                    assertTrue(server.requests.isEmpty(), "no request while the lock is held")
                    assertTrue(store.writes.isEmpty(), "no write while the lock is held")
                    assertTrue(wiped.isEmpty(), "no wipe while the lock is held")
                    jobs
                }
            jobs.joinAll()
            assertEquals(listOf("key", "records"), wiped, "each use case ran once the lock was free")
            assertTrue(server.requests.isNotEmpty())
        }

    /** "Try again" waits for the storage's lock too, so it can't reset the count in the middle of a restore (#111 CR-1). */
    @Test
    fun tryAgainWaitsForTheStoragesLock() =
        runTest {
            storage.restores.set(GuardedMlsFactory.LIMIT)
            val job =
                storage.lock.holding {
                    val job = launch { auth().tryAgain() }
                    testScheduler.advanceUntilIdle()
                    assertEquals(GuardedMlsFactory.LIMIT, storage.restores.failures(), "no reset while the lock is held")
                    job
                }
            job.join()
            assertEquals(0, storage.restores.failures())
        }

    private companion object {
        val ENGINE_GRACE = 300.milliseconds
        const val INVITE = "AAAAAAAAAAAAAAAAAAAAAA"
    }
}
