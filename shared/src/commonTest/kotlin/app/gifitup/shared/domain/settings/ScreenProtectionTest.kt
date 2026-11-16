package app.gifitup.shared.domain.settings

import app.gifitup.shared.data.storage.InMemoryLocalStore
import app.gifitup.shared.presentation.settings.ScreenProtectionStateHolder
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScreenProtectionTest {
    private val store = InMemoryLocalStore()

    @Test
    fun isOnByDefaultAndWhenTheEntryIsNotOurs() =
        runTest {
            val setting = ScreenProtectionSetting(store)
            assertTrue(setting.enabled())
            store.entries[ScreenProtectionSetting.KEY] = byteArrayOf(7, 7)
            assertTrue(setting.enabled())
            store.entries[ScreenProtectionSetting.KEY] = byteArrayOf(1)
            assertTrue(setting.enabled())
        }

    @Test
    fun theChoiceIsStoredAndRead() =
        runTest {
            val setting = ScreenProtectionSetting(store)
            assertTrue(setting.set(false))
            assertFalse(setting.enabled())
            assertTrue(setting.set(true))
            assertTrue(setting.enabled())
        }

    @Test
    fun aFailingStoreMeansProtectedAndANotStoredChoiceIsNotShown() =
        runTest {
            val broken =
                object : InMemoryLocalStore() {
                    override suspend fun get(key: String): ByteArray? = error("disk")

                    override suspend fun putAll(entries: Map<String, ByteArray?>) = error("disk")
                }
            val setting = ScreenProtectionSetting(broken)
            assertTrue(setting.enabled())
            assertFalse(setting.set(false))
            val holder = ScreenProtectionStateHolder(this, setting)
            holder.load()
            holder.set(false)
            advanceUntilIdle()
            assertTrue(holder.on.value)
        }

    @Test
    fun theHolderStartsOnLoadsTheStoredChoiceAndFollowsAChange() =
        runTest {
            ScreenProtectionSetting(store).set(false)
            val holder = ScreenProtectionStateHolder(this, ScreenProtectionSetting(store))
            assertTrue(holder.on.value) // protected from the first frame
            holder.load()
            advanceUntilIdle()
            assertFalse(holder.on.value)
            holder.set(true)
            advanceUntilIdle()
            assertEquals(true, holder.on.value)
            assertTrue(ScreenProtectionSetting(store).enabled())
        }

    @Test
    fun aSlowLoadNeverOverwritesAChoiceMadeMeanwhile() =
        runTest {
            ScreenProtectionSetting(store).set(false)
            val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
            val slow =
                object : InMemoryLocalStore() {
                    override suspend fun get(key: String): ByteArray? {
                        gate.await()
                        return super.get(key)
                    }
                }
            slow.entries.putAll(store.entries)
            val holder = ScreenProtectionStateHolder(this, ScreenProtectionSetting(slow))
            assertFalse(holder.settled.value)
            holder.load()
            holder.set(true)
            gate.complete(Unit)
            advanceUntilIdle()
            assertTrue(holder.on.value)
            assertTrue(holder.settled.value)
            assertTrue(ScreenProtectionSetting(slow).enabled())
        }
}
