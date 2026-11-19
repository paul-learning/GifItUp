package app.gifitup.shared.presentation.start

import app.gifitup.shared.data.storage.IdentityStore
import app.gifitup.shared.data.storage.InMemoryLocalStore
import app.gifitup.shared.data.storage.LocalStateException
import app.gifitup.shared.data.storage.StoredIdentity
import app.gifitup.shared.domain.auth.DeviceLock
import app.gifitup.shared.domain.auth.InMemoryIdentityStore
import app.gifitup.shared.domain.origin.ServerOriginSetting
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LoadAppStartTest {
    private val default = "https://gifitup.example"
    private val store = InMemoryLocalStore()
    private val lock = DeviceLock()

    private fun setting(identities: IdentityStore = InMemoryIdentityStore()) = ServerOriginSetting(store, identities, lock, default)

    private fun failing(problem: LocalStateException.Problem) =
        object : IdentityStore {
            override suspend fun load(): StoredIdentity = throw LocalStateException(problem)

            override suspend fun save(identity: StoredIdentity) = Unit

            override suspend fun clear() = Unit
        }

    @Test
    fun theDefaultOriginIsReadyAndNotCustom() =
        runTest {
            val ready = assertIs<AppStartState.Ready>(LoadAppStart(setting())())
            assertEquals(default, ready.origin.origin)
            assertFalse(ready.origin.custom)
        }

    @Test
    fun aChangedOriginIsReadyAndFlaggedCustom() =
        runTest {
            val setting = setting()
            setting.set("https://dev.example")
            val ready = assertIs<AppStartState.Ready>(LoadAppStart(setting)())
            assertEquals("https://dev.example", ready.origin.origin)
            assertTrue(ready.origin.custom)
        }

    @Test
    fun anIdentityThatCantBeReadIsAnInvalidState() =
        runTest {
            val state = LoadAppStart(setting(failing(LocalStateException.Problem.DAMAGED)))()
            assertEquals(AppStartState.StateInvalid, state)
        }

    @Test
    fun aStorageFailureIsAStateNotACrash() =
        runTest {
            val state = LoadAppStart(setting(failing(LocalStateException.Problem.STORAGE)))()
            assertEquals(AppStartState.StorageFailed, state)
        }
}
