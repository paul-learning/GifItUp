package app.gifitup.shared.presentation.contacts

import app.gifitup.shared.domain.contacts.Contact
import app.gifitup.shared.domain.contacts.ContactStatus
import app.gifitup.shared.domain.contacts.KeyBytes
import app.gifitup.shared.domain.groups.GroupInfo
import app.gifitup.shared.domain.groups.GroupStatus

/** Why the key a chat uses for a friend can't be saved from the Friends screen (#31). The screen words each. */
enum class KeyNote {
    /** The groups with this friend hold more than one key or leaf under the name: several phones, or someone else's leaf. */
    MORE_THAN_ONE_KEY,

    /** The engine flagged a leaf of this name as a new, unverified device. */
    UNVERIFIED_DEVICE,

    /** The server's list shows another key (or name) than the chat uses; no override. */
    LIST_DIFFERS,
}

/** What the groups on this device say about a friend who is only listed (`UNCONFIRMED`). */
internal sealed interface RosterCheck {
    /** No group in normal state has a leaf of this name: nothing to compare. */
    data object None : RosterCheck

    /**
     * The key every normal-state group uses for the name, shown as "the key this chat uses, not yet saved". [saveable]
     * only if the list agrees on name and key and no note applies (SEC-1 to SEC-3 of the #31 plan review).
     */
    class Key(
        val key: KeyBytes,
        val note: KeyNote?,
    ) : RosterCheck {
        val saveable: Boolean get() = note == null
    }

    /** More than one key or an unverified leaf: no emojis, no button. */
    class Unclear(
        val note: KeyNote,
    ) : RosterCheck
}

/**
 * Looks at [contact] (an `UNCONFIRMED` friend) in the [groups]. Only groups in normal state ([GroupStatus.ACTIVE]) count:
 * a frozen, removed or rolled-back group is not a trustworthy source. The roster's key is the key that protects the chat
 * (MLS-authenticated); the list is only matched to it, never trusted for it.
 */
internal fun checkRoster(
    contact: Contact,
    groups: List<GroupInfo>,
): RosterCheck {
    if (contact.status != ContactStatus.UNCONFIRMED) return RosterCheck.None
    val perGroup =
        groups.filter { it.status == GroupStatus.ACTIVE }.map { group -> group.leaves.filter { it.username == contact.username } }
    val leaves = perGroup.flatten()
    val keys = leaves.map { it.key }.distinct()
    return when {
        leaves.isEmpty() -> RosterCheck.None
        leaves.any { it.unverified } -> RosterCheck.Unclear(KeyNote.UNVERIFIED_DEVICE)
        perGroup.any { it.size > 1 } || keys.size > 1 -> RosterCheck.Unclear(KeyNote.MORE_THAN_ONE_KEY)
        contact.devices.keys != setOf(keys.single()) -> RosterCheck.Key(keys.single(), KeyNote.LIST_DIFFERS)
        else -> RosterCheck.Key(keys.single(), null)
    }
}
