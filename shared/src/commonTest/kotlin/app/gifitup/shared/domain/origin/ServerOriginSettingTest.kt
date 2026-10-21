package app.gifitup.shared.domain.origin

import app.gifitup.shared.data.storage.InMemoryLocalStore
import app.gifitup.shared.data.storage.LocalStateException
import app.gifitup.shared.data.storage.StoredIdentity
import app.gifitup.shared.domain.auth.DeviceLock
import app.gifitup.shared.domain.auth.InMemoryIdentityStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServerOriginSettingTest {
    private val default = "https://gifitup.example"
    private val store = InMemoryLocalStore()
    private val identities = InMemoryIdentityStore()
    private val lock = DeviceLock()
    private val setting = ServerOriginSetting(store, identities, lock, default)

    private suspend fun origin(): ServerOriginSetting.Current.Origin = assertIs(setting.current())

    @Test
    fun theDefaultAppliesUntilTheOriginIsChanged() =
        runTest {
            assertEquals(default, origin().origin)
            assertFalse(origin().custom)
            assertEquals(
                "https://dev.example:8443",
                assertIs<ServerOriginSetting.SetResult.Changed>(setting.set("  HTTPS://Dev.Example:8443\n")).origin,
            )
            assertEquals("https://dev.example:8443", origin().origin)
            assertTrue(origin().custom, "a custom origin is flagged for the banner")
        }

    @Test
    fun settingTheDefaultBackRemovesTheRecord() =
        runTest {
            setting.set("https://dev.example")
            setting.set(default)
            assertNull(store.get("server-origin"))
            assertFalse(origin().custom)
        }

    @Test
    fun onlyACanonicalHttpsOriginIsAccepted() =
        runTest {
            val bad =
                listOf(
                    "",
                    "http://dev.example",
                    "https://dev.example/",
                    "https://dev.example/path",
                    "https://user@dev.example",
                    "https://dev.example:443",
                    "https://dev.example:99999",
                    "https://dev..example",
                    "https://-dev.example",
                    "https://dév.example", // Unicode is never rendered or fixed up
                    "dev.example",
                    "https://127.0.0.1:8080/",
                )
            bad.forEach { assertIs<ServerOriginSetting.SetResult.Invalid>(setting.set(it), it) }
            assertEquals(default, origin().origin)
            assertNull(store.get("server-origin"))
        }

    @Test
    fun anIdentityDecidesTheOriginAndBlocksChanges() =
        runTest {
            setting.set("https://dev.example")
            identities.identity = StoredIdentity("https://old.example", "ann", byteArrayOf(1), deviceId = null) // pending
            assertEquals("https://old.example", origin().origin, "the identity wins over the setting")
            assertIs<ServerOriginSetting.SetResult.HasIdentity>(setting.set("https://new.example"))
            identities.identity = StoredIdentity("https://old.example", "ann", byteArrayOf(1), deviceId = "d")
            assertIs<ServerOriginSetting.SetResult.HasIdentity>(setting.set("https://new.example"))
            assertEquals("https://old.example", origin().origin)
        }

    @Test
    fun aLostSettingNeverTurnsIntoAnOriginMismatch() =
        runTest {
            identities.identity = StoredIdentity("https://old.example", "ann", byteArrayOf(1), deviceId = "d")
            assertNull(store.get("server-origin"))
            assertEquals("https://old.example", origin().origin, "no stored setting: still the identity's origin")
            store.putAll(mapOf("server-origin" to "garbage".encodeToByteArray()))
            assertEquals("https://old.example", origin().origin)
        }

    @Test
    fun anUnreadableStoredOriginCountsAsNone() =
        runTest {
            store.putAll(mapOf("server-origin" to "http://nope".encodeToByteArray()))
            assertEquals(default, origin().origin)
        }

    @Test
    fun anUnreadableIdentityBlocksChangesAndHidesTheOrigin() =
        runTest {
            val damaged =
                ServerOriginSetting(
                    store,
                    object : app.gifitup.shared.data.storage.IdentityStore {
                        override suspend fun load(): StoredIdentity = throw LocalStateException(LocalStateException.Problem.DAMAGED)

                        override suspend fun save(identity: StoredIdentity) = Unit

                        override suspend fun clear() = Unit
                    },
                    lock,
                    default,
                )
            assertIs<ServerOriginSetting.Current.Unreadable>(damaged.current())
            assertIs<ServerOriginSetting.SetResult.HasIdentity>(damaged.set("https://dev.example"))
            assertNull(store.get("server-origin"))
        }

    @Test
    fun aStorageFailureIsNotSwallowed() =
        runTest {
            val failing =
                ServerOriginSetting(
                    store,
                    object : app.gifitup.shared.data.storage.IdentityStore {
                        override suspend fun load(): StoredIdentity = throw LocalStateException(LocalStateException.Problem.STORAGE)

                        override suspend fun save(identity: StoredIdentity) = Unit

                        override suspend fun clear() = Unit
                    },
                    lock,
                    default,
                )
            assertTrue(runCatching { failing.current() }.exceptionOrNull() is LocalStateException)
        }

    @Test
    fun aChangeWaitsForTheDeviceLockSoItCannotRaceARegistration() =
        runTest {
            val release = CompletableDeferred<Unit>()
            val holding = CompletableDeferred<Unit>()
            launch {
                lock.holding {
                    holding.complete(Unit)
                    release.await()
                }
            }
            holding.await()
            var done = false
            launch {
                setting.set("https://dev.example")
                done = true
            }
            repeat(5) { yield() }
            assertFalse(done, "set() is waiting for the lock")
            // Meanwhile a registration saved its identity: the change must see it once the lock is free.
            identities.identity = StoredIdentity(default, "ann", byteArrayOf(1), deviceId = null)
            release.complete(Unit)
            repeat(5) { yield() }
            assertTrue(done)
            assertNull(store.get("server-origin"), "the identity written under the lock blocked the change")
        }

    @Test
    fun theDefaultMustBeCanonical() {
        assertTrue(runCatching { ServerOriginSetting(store, identities, lock, "http://x.example") }.isFailure)
    }
}
