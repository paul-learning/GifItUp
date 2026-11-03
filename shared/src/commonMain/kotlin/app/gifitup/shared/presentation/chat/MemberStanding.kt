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
 * The standing of each name in [leaves] against [pinned] (saved friends' usernames with their saved keys). A name without
 * leaves (a member just added, not read back yet) is a friend only if the contact book has it saved.
 */
internal fun standingsOf(
    names: List<String>,
    leaves: List<RosterLeaf>,
    pinned: Map<String, Set<KeyBytes>>,
): Map<String, MemberStanding> =
    names.associateWith { name ->
        val keys = pinned[name]
        val own = leaves.filter { it.username == name }
        when {
            keys == null -> MemberStanding.NOT_SAVED
            own.any { it.key !in keys } -> MemberStanding.OTHER_KEY
            else -> MemberStanding.FRIEND
        }
    }
