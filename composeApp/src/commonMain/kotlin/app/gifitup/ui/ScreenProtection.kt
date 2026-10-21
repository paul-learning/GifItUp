package app.gifitup.ui

/**
 * Keeps the window out of screenshots, screen recordings and the recents thumbnail while it is on (Android:
 * `FLAG_SECURE`; #17 plan review SEC-8). Bound to the window, so it is handed to [App] by the Activity and used only
 * inside the composition; the ViewModels never see it. The web has no equivalent and passes [None].
 */
fun interface ScreenProtection {
    fun protect(on: Boolean)

    companion object {
        val None = ScreenProtection { }
    }
}
