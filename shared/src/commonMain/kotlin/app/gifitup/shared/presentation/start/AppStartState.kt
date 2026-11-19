package app.gifitup.shared.presentation.start

import app.gifitup.shared.data.storage.LocalStateException
import app.gifitup.shared.domain.origin.ServerOriginSetting
import app.gifitup.shared.presentation.onboarding.OriginView

/**
 * What the app can start with (ADR-0013, #162): the answer of the origin setting, which the root screen only renders. A storage
 * failure is a state, never a crash (#131 SEC-16).
 */
sealed interface AppStartState {
    /** Not read yet (or read again after the origin changed). */
    data object Loading : AppStartState

    /** The device storage failed: the app can't tell which server it is for. */
    data object StorageFailed : AppStartState

    /** An identity record exists but can't be read: the origin is unknown, and only a wipe fixes it. */
    data object StateInvalid : AppStartState

    /** The origin the app talks to, ready for the onboarding and everything after it. */
    class Ready(
        val origin: OriginView,
    ) : AppStartState
}

/** Reads the origin setting once and turns the answer, or its failure, into an [AppStartState]. */
class LoadAppStart(
    private val setting: ServerOriginSetting,
) {
    suspend operator fun invoke(): AppStartState =
        try {
            when (val current = setting.current()) {
                is ServerOriginSetting.Current.Origin -> AppStartState.Ready(OriginView(current.origin, current.custom))
                ServerOriginSetting.Current.Unreadable -> AppStartState.StateInvalid
            }
        } catch (ignored: LocalStateException) {
            AppStartState.StorageFailed
        }
}
