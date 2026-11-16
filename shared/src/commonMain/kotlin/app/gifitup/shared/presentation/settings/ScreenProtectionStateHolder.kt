package app.gifitup.shared.presentation.settings

import app.gifitup.shared.domain.settings.ScreenProtectionSetting
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The state of the "hide chats from screenshots" switch (#281). It starts **on** and stays on until the stored choice says
 * otherwise, so the window is protected until the choice is known (fail closed). [set] changes it only once the choice is stored,
 * so the screen never shows a state that a restart would undo. Loading and setting run one after the other, and a choice the user
 * made is never overwritten by a slower load (security review SEC-2). [settled] turns true once the stored choice has been
 * read (or could not be), which is when the Activity's start-up protection may let go.
 */
class ScreenProtectionStateHolder(
    private val scope: CoroutineScope,
    private val setting: ScreenProtectionSetting,
) {
    private val flow = MutableStateFlow(true)
    val on: StateFlow<Boolean> = flow.asStateFlow()
    private val settledFlow = MutableStateFlow(false)
    val settled: StateFlow<Boolean> = settledFlow.asStateFlow()
    private val order = Mutex()
    private var chosen = false

    /** Reads the stored choice, unless the user has already made one in this run. */
    fun load() {
        scope.launch {
            order.withLock {
                if (!chosen) flow.value = setting.enabled()
                settledFlow.value = true
            }
        }
    }

    fun set(on: Boolean) {
        scope.launch {
            order.withLock {
                if (setting.set(on)) {
                    chosen = true
                    flow.value = on
                }
            }
        }
    }
}
