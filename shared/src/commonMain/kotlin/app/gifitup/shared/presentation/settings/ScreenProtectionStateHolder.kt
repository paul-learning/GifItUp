package app.gifitup.shared.presentation.settings

import app.gifitup.shared.domain.settings.ScreenProtectionSetting
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The state of the "hide chats from screenshots" switch (#281). It starts **on** and stays on until the stored choice says
 * otherwise, so the window is protected from the first frame (fail closed). [set] changes it only once the choice is stored, so
 * the screen never shows a state that a restart would undo.
 */
class ScreenProtectionStateHolder(
    private val scope: CoroutineScope,
    private val setting: ScreenProtectionSetting,
) {
    private val flow = MutableStateFlow(true)
    val on: StateFlow<Boolean> = flow.asStateFlow()

    /** Reads the stored choice. */
    fun load() {
        scope.launch { flow.value = setting.enabled() }
    }

    fun set(on: Boolean) {
        scope.launch { if (setting.set(on)) flow.value = on }
    }
}
