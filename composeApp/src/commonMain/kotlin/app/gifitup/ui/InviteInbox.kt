package app.gifitup.ui

/**
 * An invite link the platform's entry point took (the web page's address), handed to the onboarding **once**: [take]
 * returns it and forgets it, and [drop] forgets it unused (an origin change must never pick it up, #131 SEC-14).
 * Held in memory only, outside any route or saved state.
 */
class InviteInbox(
    link: String?,
) {
    private var link: String? = link

    fun take(): String? = link.also { link = null }

    fun drop() {
        link = null
    }
}
