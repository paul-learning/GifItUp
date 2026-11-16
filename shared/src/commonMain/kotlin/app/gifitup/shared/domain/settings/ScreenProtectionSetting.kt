package app.gifitup.shared.domain.settings

import app.gifitup.shared.data.storage.LocalStore
import kotlin.coroutines.cancellation.CancellationException

/**
 * Whether the app keeps its windows out of screenshots, screen recordings and the recents thumbnail (#281, Android
 * `FLAG_SECURE`; the web has no equivalent). On unless the user turned it off, and **on whenever it can't be read**: a store
 * that fails, a missing entry or a blob that isn't ours all mean "protected" (fail closed). One byte in the device's
 * [LocalStore] (ADR-0011): `0` for off, anything else is on. A public preference, not a secret; it goes with the store.
 */
class ScreenProtectionSetting(
    private val store: LocalStore,
) {
    suspend fun enabled(): Boolean =
        try {
            val bytes = store.get(KEY)
            !(bytes != null && bytes.size == 1 && bytes[0] == OFF)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            true
        }

    /** Stores the choice; `false` if the device's storage refused (the caller keeps showing the old state). */
    suspend fun set(on: Boolean): Boolean =
        try {
            store.putAll(mapOf(KEY to byteArrayOf(if (on) ON else OFF)))
            true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }

    companion object {
        const val KEY = "screen-protection.v1"
        private const val OFF: Byte = 0
        private const val ON: Byte = 1
    }
}
