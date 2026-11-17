package app.gifitup.shared.presentation.settings

/**
 * One holder's share of a counted resource (the window protection, #281): [set] takes the share when it is wanted and lets it go
 * when it is not, **once each way however often it is called**, so the count can't drift whatever order the callers' effects
 * run in. The first version of the app-wide protection acquired and released from a Compose effect whose `onDispose` read the
 * *current* state instead of the one it was created with; it never released, and the window stayed protected after the switch
 * was turned off. This class holds the one fact that matters (do I hold it?) and is tested on the JVM.
 */
class HeldShare(
    private val acquire: () -> Unit,
    private val release: () -> Unit,
) {
    private var held = false

    fun set(wanted: Boolean) {
        if (wanted == held) return
        held = wanted
        if (wanted) acquire() else release()
    }
}
