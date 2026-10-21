package app.gifitup.shared.presentation.settings

import app.gifitup.shared.data.storage.IdentityStore
import app.gifitup.shared.data.storage.InMemoryLocalStore
import app.gifitup.shared.data.storage.LocalStateException
import app.gifitup.shared.data.storage.StoredIdentity
import app.gifitup.shared.domain.auth.DeviceLock
import app.gifitup.shared.domain.auth.InMemoryIdentityStore
import app.gifitup.shared.domain.origin.ServerOriginSetting
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class ServerSettingsStateHolderTest {
    private val identities = InMemoryIdentityStore()
    private val setting = ServerOriginSetting(InMemoryLocalStore(), identities, DeviceLock(), "https://gifitup.example")
    private var changes = 0

    @Test
    fun aValidOriginIsAppliedAndReported() =
        runTest {
            val holder = ServerSettingsStateHolder(this, setting) { changes++ }
            holder.apply(" HTTPS://Dev.Example ")
            advanceUntilIdle()
            assertNull(holder.problem.value)
            assertEquals(1, changes)
            assertEquals("https://dev.example", assertIs<ServerOriginSetting.Current.Origin>(setting.current()).origin)
        }

    @Test
    fun refusalsAreProblemsAndChangeNothing() =
        runTest {
            val holder = ServerSettingsStateHolder(this, setting) { changes++ }
            holder.apply("http://dev.example")
            advanceUntilIdle()
            assertEquals(ServerSettingsProblem.INVALID, holder.problem.value)
            identities.identity = StoredIdentity("https://gifitup.example", "ann", byteArrayOf(1), deviceId = "d")
            holder.apply("https://dev.example")
            advanceUntilIdle()
            assertEquals(ServerSettingsProblem.HAS_IDENTITY, holder.problem.value)
            assertEquals(0, changes)
        }

    @Test
    fun aStorageFailureIsAProblemNotACrash() =
        runTest {
            val broken =
                object : IdentityStore {
                    override suspend fun load(): StoredIdentity = throw LocalStateException(LocalStateException.Problem.STORAGE)

                    override suspend fun save(identity: StoredIdentity) = Unit

                    override suspend fun clear() = Unit
                }
            val failing = ServerOriginSetting(InMemoryLocalStore(), broken, DeviceLock(), "https://gifitup.example")
            val holder = ServerSettingsStateHolder(this, failing) { changes++ }
            holder.apply("https://dev.example")
            advanceUntilIdle()
            assertEquals(ServerSettingsProblem.STORAGE_FAILED, holder.problem.value)
            assertEquals(0, changes)
        }
}
