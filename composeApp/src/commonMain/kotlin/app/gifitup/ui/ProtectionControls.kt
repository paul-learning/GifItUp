package app.gifitup.ui

import app.gifitup.shared.presentation.settings.ScreenProtectionStateHolder
import kotlinx.coroutines.flow.StateFlow

/** What the Profile screen needs for the "hide chats from screenshots" switch (#281): its state and how to change it. */
internal class ProtectionControls(
    val on: StateFlow<Boolean>,
    val set: (Boolean) -> Unit,
) {
    companion object {
        fun of(holder: ScreenProtectionStateHolder) = ProtectionControls(holder.on, holder::set)
    }
}
