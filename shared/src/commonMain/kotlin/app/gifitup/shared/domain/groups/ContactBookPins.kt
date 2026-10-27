package app.gifitup.shared.domain.groups

import app.gifitup.shared.domain.contacts.ContactBookAccess
import app.gifitup.shared.domain.contacts.ContactLoad
import app.gifitup.shared.domain.contacts.ContactStatus
import app.gifitup.shared.domain.contacts.KeyBytes

/**
 * The contact book (ADR-0014) as the sync engine's pins (ADR-0016). A leaf whose name is no contact is a stranger, a
 * normal member of the group; a leaf that carries a pinned key of an `OK` contact is pinned; any other key under the name
 * of a contact the book trusts or blocks is a mismatch. A contact the member list only mentioned (`UNCONFIRMED`) has no
 * pin yet, so it counts as a stranger. A book that is missing or damaged is no lookup at all (`null`): the engine stages
 * nothing and freezes nothing until it can be read.
 */
class ContactBookPins(
    private val access: ContactBookAccess,
) : ContactPinsSource {
    override suspend fun load(): ContactPins? {
        val loaded = access.read() as? ContactLoad.Book
        val byName =
            loaded
                ?.book
                ?.contacts
                ?.values
                ?.groupBy { it.username }
        return byName?.let { contactsByName ->
            ContactPins { username, signatureKey ->
                val named = contactsByName[username].orEmpty()
                val key = KeyBytes(signatureKey)
                when {
                    named.any { it.status == ContactStatus.BLOCKED } -> PinCheck.MISMATCH
                    named.any { it.status == ContactStatus.OK && key in it.devices } -> PinCheck.PINNED
                    named.any { it.status != ContactStatus.UNCONFIRMED } -> PinCheck.MISMATCH
                    else -> PinCheck.STRANGER
                }
            }
        }
    }
}
