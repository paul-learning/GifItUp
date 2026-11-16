package app.gifitup.ui

/**
 * Keeps the window out of screenshots, screen recordings and the recents thumbnail while it is on (Android: `FLAG_SECURE`; #17
 * plan review SEC-8, and #281 for the chat screens). Bound to the window, so it is handed to [App] by the Activity and used only
 * inside the composition; the ViewModels never see it. **Counted**: every `protect(true)` is answered by one `protect(false)`, and
 * the window stays protected while any is open, so the invite screen, the diagnostics and the chats can overlap without one
 * lifting the other's protection. The web has no equivalent and passes [None] ([supported] false: the app shows no switch).
 */
interface ScreenProtection {
    val supported: Boolean
        get() = true

    fun protect(on: Boolean)

    companion object {
        val None =
            object : ScreenProtection {
                override val supported = false

                override fun protect(on: Boolean) = Unit
            }
    }
}
