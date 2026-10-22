package app.gifitup.shared.domain.invite

/**
 * What an app's entry point may take from the intent (or address) that started it (ADR-0013, #17 PR 4). That input is
 * untrusted: any app can send an intent to an exported activity. So only a link opened by the user's action
 * (`isView`), only at a **fresh launch** (a rotation or a restore after process death hands the activity its old launch
 * intent again, and the link was used or dropped long ago), only an `https` link, and only a bounded one. This is a
 * cheap first filter: [InviteLinks.parse] decides whether it is a link for the configured server, and a link for
 * another origin is "not for this server" and never a switch.
 */
object LaunchLink {
    const val MAX_LENGTH = 300

    fun candidate(
        isView: Boolean,
        data: String?,
        freshLaunch: Boolean,
    ): String? = data?.takeIf { isView && freshLaunch && it.length <= MAX_LENGTH && it.startsWith("https://") }
}
