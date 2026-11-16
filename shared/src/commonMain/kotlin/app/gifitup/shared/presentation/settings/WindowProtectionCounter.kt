package app.gifitup.shared.presentation.settings

/**
 * The count behind the window's screenshot protection (#281): the window is protected while any holder is open. Holders are the
 * screens that protect themselves (the invite screen, the diagnostics, the app-wide setting) and the **start-up hold**, which the
 * Activity takes in `onCreate` before anything is drawn and lets go of with [settled] once the stored choice is known (security
 * review SEC-1). [apply] sets or clears the platform flag and is called on every change. Every [acquire] is answered by one
 * [release]; a release without an open holder does nothing, so it can never lower another holder's share. A plain class, so the
 * counting is tested on the JVM and not only compiled for the Android app.
 */
class WindowProtectionCounter(
    private val apply: (Boolean) -> Unit,
) {
    private var open = 0
    private var startup = false

    fun acquire() {
        open++
        apply(true)
    }

    fun release() {
        if (open > 0) open--
        apply(open > 0)
    }

    /** The start-up hold, once however often it is called. */
    fun holdUntilSettled() {
        if (!startup) {
            startup = true
            acquire()
        }
    }

    /** Lets go of the start-up hold, once; a second call does nothing. */
    fun settled() {
        if (startup) {
            startup = false
            release()
        }
    }
}
