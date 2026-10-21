package app.gifitup.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.gifitup.shared.domain.auth.DeviceStorage
import app.gifitup.shared.domain.origin.ServerOriginSetting
import app.gifitup.shared.presentation.onboarding.OnboardingStateHolder

/**
 * The androidx wrapper around the onboarding state holder (ADR-0013): it gives the holder the main-thread
 * `viewModelScope` and survives recomposition and configuration changes. It keeps nothing in saved state: the invite
 * code lives in the holder's memory only, so a process death simply starts the flow again (and a pending
 * registration is finished without the code).
 */
internal class OnboardingViewModel(
    services: AppServices,
    setting: ServerOriginSetting,
    storage: DeviceStorage,
) : ViewModel() {
    val holder =
        OnboardingStateHolder(
            scope = viewModelScope,
            origin = setting,
            identities = storage.identities,
            register = services.auth.register,
            refill = services.auth.refillKeyPackages,
            sessions = services.sessions,
        )

    init {
        holder.start()
    }
}
