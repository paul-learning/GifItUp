package app.gifitup.shared.presentation.chat

import app.gifitup.shared.domain.contacts.KeyBytes
import app.gifitup.shared.domain.groups.RosterLeaf

/** How a member of a group stands with this device's friends (#31). Decided by key against the saved pin, never by name alone. */
enum class MemberStanding {
    /** A saved friend, and every leaf of the name has the saved key. */
    FRIEND,

    /** Nobody saved under this name: someone the group's members brought in, or a friend not saved yet. */
    NOT_SAVED,

    /** The name is a saved friend's, but a leaf of the group carries another key than the saved one: not that friend. */
    OTHER_KEY,
}

/**
 * The standing of each name in [leaves] against [pinned] (saved friends' usernames with their saved keys). A name
 * **without a leaf** (the roster could not be read back) gets no standing at all: it is never a friend by its name.
 */
internal fun standingsOf(
    names: List<String>,
    leaves: List<RosterLeaf>,
    pinned: Map<String, Set<KeyBytes>>,
): Map<String, MemberStanding> =
    names
        .mapNotNull { name ->
            val keys = pinned[name]
            val own = leaves.filter { it.username == name }
            when {
                own.isEmpty() -> null
                keys == null -> name to MemberStanding.NOT_SAVED
                own.any { it.key !in keys } -> name to MemberStanding.OTHER_KEY
                else -> name to MemberStanding.FRIEND
            }
        }.toMap()
