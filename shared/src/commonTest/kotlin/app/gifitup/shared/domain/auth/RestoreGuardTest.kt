package app.gifitup.shared.domain.auth

import app.gifitup.shared.data.crypto.FakeMlsClient
import app.gifitup.shared.data.crypto.MlsClient
import app.gifitup.shared.data.crypto.MlsClientFactory
import app.gifitup.shared.data.crypto.MlsErrorCode
import app.gifitup.shared.data.crypto.MlsException
import app.gifitup.shared.data.network.FakeAuthServer
import app.gifitup.shared.data.network.FakeAuthServer.Companion.DEVICE_ID
import app.gifitup.shared.data.network.Session
import app.gifitup.shared.data.storage.InMemoryLocalStore
import app.gifitup.shared.data.storage.LocalStateException
import app.gifitup.shared.data.storage.LocalStore
import app.gifitup.shared.data.storage.SnapshotKeyStore
import app.gifitup.shared.data.storage.StoredIdentity
import app.gifitup.shared.data.storage.StoredRestoreCounter
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** [GuardedMlsFactory] and the use cases that restore through it (#16 SEC-4). */
class RestoreGuardTest {
    private val store = InMemoryLocalStore()
    private val counter = StoredRestoreCounter(store)

    /** Restores like the fake, or "traps": the real wrapper reports a trap as `MlsException(Poisoned)` (#108 SEC-21). */
    private class Trapping : MlsClientFactory by FakeMlsClient.Factory {
        var trap = false
        var restores = 0

        override fun restore(
            state: ByteArray,
            snapshotKey: ByteArray,
        ): MlsClient {
            restores++
            if (trap) throw MlsException(MlsErrorCode.Poisoned)
            return FakeMlsClient.Factory.restore(state, snapshotKey)
        }
    }

    private val mls = Trapping()
    private val guarded = GuardedMlsFactory(mls, counter)

    private fun state(): Pair<ByteArray, ByteArray> {
        val key = ByteArray(32) { 3 }
        val client = FakeMlsClient.Factory.create("ann", key.copyOf())
        return client.exportState() to key
    }

    @Test
    fun blocksAfterThreeRestoresThatNeverReturnedUntilTryAgain() =
        runTest {
            val (snapshot, key) = state()
            mls.trap = true
            repeat(GuardedMlsFactory.LIMIT) {
                assertEquals(MlsErrorCode.Poisoned, assertFailsWith<MlsException> { guarded.restore(snapshot, key.copyOf()) }.code)
            }
            assertEquals(3, counter.failures())
            mls.trap = false
            val blockedKey = key.copyOf()
            assertFailsWith<RestoreBlockedException> { guarded.restore(snapshot, blockedKey) }
            assertEquals(GuardedMlsFactory.LIMIT, mls.restores, "a blocked restore never reaches the client")
            assertTrue(blockedKey.all { it == 0.toByte() }, "the key is zeroed when refused")
            guarded.tryAgain()
            guarded.restore(snapshot, key.copyOf()).close()
            assertEquals(0, counter.failures())
        }

    /** A snapshot that traps on every restore ends in RESTORE_BLOCKED at the use case, so the reload loop stops (#108 SEC-21). */
    @Test
    fun aSnapshotThatAlwaysTrapsEndsBlocked() =
        runTest {
            val (snapshot, key) = state()
            val identities = InMemoryIdentityStore(StoredIdentity(server.api().endpoint.origin, "ann", snapshot, DEVICE_ID))
            val keys = InMemoryKeyStore(key)
            val logIn = LogIn(server.api(), guarded, identities, keys, DeviceLock())
            mls.trap = true
            repeat(GuardedMlsFactory.LIMIT) { assertEquals(AuthError.Local.CRYPTO, assertIs<AuthResult.Failed>(logIn()).error) }
            assertEquals(AuthError.Local.RESTORE_BLOCKED, assertIs<AuthResult.Failed>(logIn()).error)
            assertEquals(GuardedMlsFactory.LIMIT, mls.restores)
        }

    /** A clean failure comes back to Kotlin, so it's no loop and never pushes the user towards a reset (#108 CR-1). */
    @Test
    fun aRestoreThatFailsCleanlyDoesntCount() =
        runTest {
            val (snapshot, _) = state()
            repeat(GuardedMlsFactory.LIMIT + 1) {
                assertEquals(
                    MlsErrorCode.SnapshotAuth,
                    assertFailsWith<MlsException> { guarded.restore(snapshot, ByteArray(32) { 9 }) }.code,
                )
            }
            assertEquals(0, counter.failures())
        }

    /** Overlapping restores run one at a time, so each is counted and none undercounts (#108 CR-3). */
    @Test
    fun overlappingRestoresAreCountedOneAtATime() =
        runTest {
            val (snapshot, key) = state()
            mls.trap = true
            List(GuardedMlsFactory.LIMIT) { async { runCatching { guarded.restore(snapshot, key.copyOf()) } } }.awaitAll()
            assertEquals(GuardedMlsFactory.LIMIT, counter.failures())
        }

    @Test
    fun theCountIsDurableBeforeTheRestoreStarts() =
        runTest {
            val (snapshot, key) = state()
            guarded.restore(snapshot, key.copyOf()).close()
            // Two writes: the increment before the restore, the reset after it.
            assertEquals(listOf<Byte>(1, 0), store.writes.map { it.getValue(StoredRestoreCounter.KEY)!!.single() })
        }

    private val server = FakeAuthServer()

    /** A store that fails: the key never reaches the client, and the use case returns a closed result (#108 SEC-13). */
    @Test
    fun aFailingStoreZeroesTheKeyAndGivesAClosedResult() =
        runTest {
            val broken =
                object : LocalStore {
                    override suspend fun get(key: String): ByteArray = throw LocalStateException(LocalStateException.Problem.STORAGE)

                    override suspend fun putAll(entries: Map<String, ByteArray?>) =
                        throw LocalStateException(LocalStateException.Problem.STORAGE)
                }
            val brokenGuard = GuardedMlsFactory(mls, StoredRestoreCounter(broken))
            val (snapshot, key) = state()
            val passed = key.copyOf()
            assertFailsWith<LocalStateException> { brokenGuard.restore(snapshot, passed) }
            assertTrue(passed.all { it == 0.toByte() })
            assertEquals(0, mls.restores)
            val keys = InMemoryKeyStore(key.copyOf())
            val identities = InMemoryIdentityStore(StoredIdentity(server.api().endpoint.origin, "ann", snapshot, DEVICE_ID))
            val result = LogIn(server.api(), brokenGuard, identities, keys, DeviceLock())()
            assertEquals(AuthError.Local.STORAGE_FAILED, assertIs<AuthResult.Failed>(result).error)
        }

    private class UnavailableKeys : SnapshotKeyStore {
        override suspend fun load(): ByteArray = throw LocalStateException(LocalStateException.Problem.KEY_UNAVAILABLE)

        override suspend fun save(key: ByteArray) = Unit

        override suspend fun clear() = Unit
    }

    /** A failed unwrap is its own error and never counts towards a reset (#16 SEC-4). */
    @Test
    fun anUnavailableKeyNeverCountsAsAFailedRestore() =
        runTest {
            val identities = InMemoryIdentityStore(StoredIdentity(server.api().endpoint.origin, "ann", byteArrayOf(1), DEVICE_ID))
            val logIn = LogIn(server.api(), guarded, identities, UnavailableKeys(), DeviceLock())
            repeat(5) { assertEquals(AuthError.Local.KEY_UNAVAILABLE, assertIs<AuthResult.Failed>(logIn()).error) }
            assertEquals(0, counter.failures())
            assertEquals(0, mls.restores)
        }

    @Test
    fun logInAndRegisterDevicesResumeBothGoThroughTheGuard() =
        runTest {
            val identities = InMemoryIdentityStore()
            val keys = InMemoryKeyStore()
            // A pending registration whose reply was lost, so the next register resumes it.
            server.on("/accounts", FakeAuthServer.dropped)
            RegisterDevice(server.api(), guarded, identities, keys, CountingRandom(), DeviceLock())("ann", INVITE)
            counter.set(GuardedMlsFactory.LIMIT)
            val resumed = RegisterDevice(server.api(), guarded, identities, keys, CountingRandom(), DeviceLock())("ann", INVITE)
            assertEquals(AuthError.Local.RESTORE_BLOCKED, assertIs<AuthResult.Failed>(resumed).error)
            identities.identity = identities.identity!!.let { StoredIdentity(it.origin, it.username, it.snapshot, DEVICE_ID) }
            val loggedIn = LogIn(server.api(), guarded, identities, keys, DeviceLock())()
            assertEquals(AuthError.Local.RESTORE_BLOCKED, assertIs<AuthResult.Failed>(loggedIn).error)
            guarded.tryAgain()
            assertIs<AuthResult.Ok<Session>>(LogIn(server.api(), guarded, identities, keys, DeviceLock())())
        }

    private companion object {
        const val INVITE = "AAAAAAAAAAAAAAAAAAAAAA"
    }
}
