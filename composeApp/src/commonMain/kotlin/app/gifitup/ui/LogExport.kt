package app.gifitup.ui

/**
 * How the diagnostics report leaves the app, by the user's own tap (#154 follow-up). Bound to the window like
 * [ScreenProtection]: the Activity hands it to [App] and it is used only inside the composition, never kept by a ViewModel.
 * [share] opens the system's share sheet (Android only: [canShare]); [save] lets the user put the text in a file of their
 * choice (Android's file picker, the browser's download). Neither logs the text, and the file name is always the same.
 */
interface LogExport {
    val canShare: Boolean

    fun share(text: String)

    fun save(text: String)

    companion object {
        /** Where there is no way out: the buttons are hidden. */
        val None =
            object : LogExport {
                override val canShare = false

                override fun share(text: String) = Unit

                override fun save(text: String) = Unit
            }

        /** The one file name a saved report gets. */
        const val FILE_NAME = "gifitup-log.txt"
    }
}
