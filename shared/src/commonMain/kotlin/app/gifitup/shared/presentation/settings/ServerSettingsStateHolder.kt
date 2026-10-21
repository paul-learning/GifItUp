package app.gifitup.shared.presentation.settings

import app.gifitup.shared.data.storage.LocalStateException
import app.gifitup.shared.domain.origin.ServerOriginSetting
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Why the server could not be changed; the screen maps each to a fixed text. */
enum class ServerSettingsProblem {
    /** Not a canonical `https` origin after trimming and lowercasing. */
    INVALID,

    /** The device has an identity on its server and stays there (ADR-0013). */
    HAS_IDENTITY,

    /** The device's storage failed; can be transient. */
    STORAGE_FAILED,
}

/**
 * The server settings screen's logic (ADR-0013): the only place the origin changes, from text the user typed.
 * [onChanged] runs after a change, so the caller rebuilds everything bound to the old origin.
 */
class ServerSettingsStateHolder(
    private val scope: CoroutineScope,
    private val setting: ServerOriginSetting,
    private val onChanged: () -> Unit,
) {
    private val flow = MutableStateFlow<ServerSettingsProblem?>(null)
    val problem: StateFlow<ServerSettingsProblem?> = flow.asStateFlow()

    fun apply(text: String) {
        scope.launch {
            val result =
                try {
                    setting.set(text)
                } catch (ignored: LocalStateException) {
                    null
                }
            flow.value =
                when (result) {
                    is ServerOriginSetting.SetResult.Changed -> null
                    ServerOriginSetting.SetResult.Invalid -> ServerSettingsProblem.INVALID
                    ServerOriginSetting.SetResult.HasIdentity -> ServerSettingsProblem.HAS_IDENTITY
                    null -> ServerSettingsProblem.STORAGE_FAILED
                }
            if (result is ServerOriginSetting.SetResult.Changed) onChanged()
        }
    }
}
