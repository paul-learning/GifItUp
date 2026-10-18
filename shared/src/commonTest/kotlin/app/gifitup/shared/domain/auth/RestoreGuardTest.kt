package app.gifitup.shared.domain.auth

import app.gifitup.shared.data.crypto.FakeMlsClient
import app.gifitup.shared.data.crypto.MlsClient
import app.gifitup.shared.data.crypto.MlsClientFactory
import app.gifitup.shared.data.network.FakeAuthServer
import app.gifitup.shared.data.network.FakeAuthServer.Companion.DEVICE_ID
import app.gifitup.shared.data.network.Session
import app.gifitup.shared.data.storage.InMemoryLocalStore
import app.gifitup.shared.data.storage.LocalStateException
import app.gifitup.shared.data.storage.SnapshotKeyStore
import app.gifitup.shared.data.storage.StoredIdentity
import app.gifitup.shared.data.storage.StoredRestoreCounter
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

    /** Restores like the fake, or "traps": throws something that isn't an MlsException, as a reload would end the call. */
    private class Trapping : MlsClientFactory by FakeMlsClient.Factory {
        var trap = false
        var restores = 0

        override fun restore(
            state: ByteArray,
            snapshotKey: ByteArray,
        ): MlsClient {
            restores++
            check(!trap) { "trap" }
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
            repeat(GuardedMlsFactory.LIMIT) { assertFailsWith<IllegalStateException> { guarded.restore(snapshot, key.copyOf()) } }
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

    @Test
    fun theCountIsDurableBeforeTheRestoreStarts() =
        runTest {
            val (snapshot, key) = state()
            guarded.restore(snapshot, key.copyOf()).close()
            // Two writes: the increment before the restore, the reset after it.
            assertEquals(listOf<Byte>(1, 0), store.writes.map { it.getValue(StoredRestoreCounter.KEY)!!.single() })
        }

    private val server = FakeAuthServer()

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
            val logIn = LogIn(server.api(), guarded, identities, UnavailableKeys())
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
            RegisterDevice(server.api(), guarded, identities, keys, CountingRandom())("ann", INVITE)
            counter.set(GuardedMlsFactory.LIMIT)
            val resumed = RegisterDevice(server.api(), guarded, identities, keys, CountingRandom())("ann", INVITE)
            assertEquals(AuthError.Local.RESTORE_BLOCKED, assertIs<AuthResult.Failed>(resumed).error)
            identities.identity = identities.identity!!.let { StoredIdentity(it.origin, it.username, it.snapshot, DEVICE_ID) }
            val loggedIn = LogIn(server.api(), guarded, identities, keys)()
            assertEquals(AuthError.Local.RESTORE_BLOCKED, assertIs<AuthResult.Failed>(loggedIn).error)
            guarded.tryAgain()
            assertIs<AuthResult.Ok<Session>>(LogIn(server.api(), guarded, identities, keys)())
        }

    private companion object {
        const val INVITE = "AAAAAAAAAAAAAAAAAAAAAA"
    }
}
