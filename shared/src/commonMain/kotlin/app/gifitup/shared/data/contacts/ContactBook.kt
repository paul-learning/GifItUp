package app.gifitup.shared.data.contacts

/** A signature key (32 bytes) with value equality, so it can key a map. The device id next to it is only a label. */
class KeyBytes(
    val bytes: ByteArray,
) {
    override fun equals(other: Any?): Boolean = other is KeyBytes && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = bytes.contentHashCode()

    override fun toString(): String = "KeyBytes"
}

/**
 * [UNCONFIRMED]: only the server's member list has shown this account; it is no pin and is replaced by a claim.
 * [BLOCKED]: sticky, see [Contact.pending]. [GONE]: pins kept, but a complete list or a claim showed the name elsewhere.
 */
enum class ContactStatus { OK, UNCONFIRMED, BLOCKED, GONE }

enum class TrustConflict { KEY_CHANGED, DEVICE_ADDED, RENAMED, USERNAME_REASSIGNED, KEY_CONFLICT, MULTIPLE_DEVICES, BOOK_FULL }

/** What was seen, and why it was refused. A blocked contact is pinned again only to exactly this ([ContactBook.accept]). */
data class Observation(
    val reason: TrustConflict,
    val username: String,
    val devices: Map<KeyBytes, String>,
)

/**
 * One account. [devices] are the pinned signature keys (key to device id label); for an [ContactStatus.UNCONFIRMED]
 * entry they are only what the list said. A [ContactStatus.BLOCKED] contact always has a [pending] observation; it
 * never overwrites the pins.
 */
data class Contact(
    val accountId: String,
    val username: String,
    val status: ContactStatus,
    val devices: Map<KeyBytes, String>,
    val pending: Observation?,
    val firstSeen: Long,
    val lastSeen: Long,
)

/** A claimed or listed account as seen once: the keys of a claim come from the KeyPackage credential. */
class Sighting(
    val accountId: String,
    val username: String,
    val devices: Map<KeyBytes, String>,
)

sealed interface Verdict {
    data object Usable : Verdict

    data class Blocked(
        val reason: TrustConflict,
    ) : Verdict
}

sealed interface AcceptResult {
    data object Accepted : AcceptResult

    /** The pending observation is not the one shown: show it again. */
    data object Changed : AcceptResult

    /** Not blocked, unknown, or the observation can't be pinned (its key is another account's). */
    data object Refused : AcceptResult
}

/**
 * The contact book (ADR-0014): trust on first use, pinned by account id and signature key. An immutable value; every
 * transition returns a new book, and nothing here does I/O. A sighting that conflicts with a pin never changes the pin:
 * it blocks the contact and is kept as [Contact.pending], and a later good sighting does not clear that.
 */
@Suppress("TooManyFunctions") // The rules of one value type; splitting them would scatter ADR-0014.
class ContactBook(
    val contacts: Map<String, Contact> = emptyMap(),
) {
    val pinCount: Int get() = contacts.values.count { it.status != ContactStatus.UNCONFIRMED }
    private val unconfirmedCount: Int get() = contacts.values.count { it.status == ContactStatus.UNCONFIRMED }

    /** The reason a contact with this username is blocked, or null. Checked before a claim so no KeyPackage is burned. */
    fun blockedReasonFor(username: String): TrustConflict? =
        contacts.values
            .firstOrNull { it.status == ContactStatus.BLOCKED && it.username == username }
            ?.pending
            ?.reason

    /** A claim is judged as a whole: any conflict blocks every device in it, and nothing is pinned. */
    fun claim(
        sighting: Sighting,
        now: Long,
    ): Pair<ContactBook, Verdict> {
        val known = contacts[sighting.accountId]?.takeIf { it.status != ContactStatus.UNCONFIRMED }
        val reason = conflict(sighting, known)
        return when {
            reason != null -> block(sighting, known, reason, now)
            known == null -> pinNew(sighting, now)
            known.status == ContactStatus.BLOCKED -> this to Verdict.Blocked(checkNotNull(known.pending).reason)
            else -> with(known.copy(status = ContactStatus.OK, lastSeen = now)) to Verdict.Usable
        }
    }

    /**
     * A member list. Absence only counts when it is [complete] (the server did not cut it). The list never pins:
     * an unknown account becomes [ContactStatus.UNCONFIRMED], a known one is checked like a claim, and list-only
     * evidence never marks another account [ContactStatus.GONE] because of a name.
     */
    fun list(
        entries: List<Sighting>,
        complete: Boolean,
        now: Long,
    ): ContactBook {
        var book = this
        entries.forEach { book = book.seeInList(it, now) }
        return if (complete) book.dropAbsent(entries.map { it.accountId }.toSet(), now) else book
    }

    /** Pins exactly [expected] if it is still the pending observation of [accountId]. */
    fun accept(
        accountId: String,
        expected: Observation,
    ): Pair<ContactBook, AcceptResult> {
        val contact = contacts[accountId]
        return when {
            contact == null || contact.status != ContactStatus.BLOCKED -> {
                this to AcceptResult.Refused
            }

            contact.pending != expected -> {
                this to AcceptResult.Changed
            }

            keyHeldByOther(expected.devices.keys, accountId) -> {
                this to AcceptResult.Refused
            }

            holderOfName(expected.username, accountId)?.takeIf { it.status != ContactStatus.GONE } != null -> {
                this to AcceptResult.Refused
            }

            else -> {
                with(accepted(contact, expected)) to AcceptResult.Accepted
            }
        }
    }

    /** The observation replaces the pins of the devices it names (by id) and keeps the others: a claim shows only usable ones. */
    private fun accepted(
        contact: Contact,
        expected: Observation,
    ): Contact =
        contact.copy(
            username = expected.username,
            status = ContactStatus.OK,
            devices = contact.devices.filterValues { it !in expected.devices.values } + expected.devices,
            pending = null,
        )

    private fun conflict(
        sighting: Sighting,
        known: Contact?,
    ): TrustConflict? =
        when {
            keyHeldByOther(sighting.devices.keys, sighting.accountId) -> TrustConflict.KEY_CONFLICT
            known != null -> conflictWithPins(sighting, known)
            holderOfName(sighting.username, sighting.accountId) != null -> TrustConflict.USERNAME_REASSIGNED
            sighting.devices.size > 1 -> TrustConflict.MULTIPLE_DEVICES
            else -> null
        }

    private fun conflictWithPins(
        sighting: Sighting,
        known: Contact,
    ): TrustConflict? {
        if (known.username != sighting.username) return TrustConflict.RENAMED
        val added = sighting.devices.filterKeys { it !in known.devices }
        return when {
            added.isEmpty() -> null
            added.values.any { it in known.devices.values } -> TrustConflict.KEY_CHANGED
            else -> TrustConflict.DEVICE_ADDED
        }
    }

    /** A pinned (not merely listed) key of another account. */
    private fun keyHeldByOther(
        keys: Set<KeyBytes>,
        accountId: String,
    ): Boolean =
        contacts.values.any {
            it.accountId != accountId && it.status != ContactStatus.UNCONFIRMED &&
                keys.any { k -> k in it.devices }
        }

    private fun holderOfName(
        username: String,
        accountId: String,
    ): Contact? =
        contacts.values.firstOrNull {
            it.accountId != accountId && it.status != ContactStatus.UNCONFIRMED &&
                it.username == username
        }

    private fun with(contact: Contact) = ContactBook(contacts + (contact.accountId to contact))

    /**
     * Cases where nothing is stored: the book is full, or the key belongs to another account (nothing to accept, and an
     * entry would block the name for good, SEC-22).
     */
    private fun refusedWithoutEntry(
        known: Contact?,
        reason: TrustConflict,
    ): TrustConflict? =
        when {
            known != null -> null
            pinCount >= MAX_PINS -> TrustConflict.BOOK_FULL
            reason == TrustConflict.KEY_CONFLICT -> reason
            else -> null
        }

    private fun block(
        sighting: Sighting,
        known: Contact?,
        reason: TrustConflict,
        now: Long,
    ): Pair<ContactBook, Verdict> {
        val refused = refusedWithoutEntry(known, reason)
        if (refused != null) return this to Verdict.Blocked(refused)
        val observed = Observation(reason, sighting.username, sighting.devices)
        val entry =
            (known ?: Contact(sighting.accountId, sighting.username, ContactStatus.BLOCKED, emptyMap(), null, now, now))
                .copy(status = ContactStatus.BLOCKED, pending = observed, lastSeen = now)
        // Claim evidence for a name that moved: the old holder keeps its pins but can no longer be "the same person".
        val holder = holderOfName(sighting.username, sighting.accountId)
        val gone =
            if (reason == TrustConflict.USERNAME_REASSIGNED &&
                holder?.status == ContactStatus.OK
            ) {
                holder.copy(status = ContactStatus.GONE)
            } else {
                null
            }
        return ContactBook(contacts + (entry.accountId to entry) + listOfNotNull(gone?.let { it.accountId to it })) to
            Verdict.Blocked(reason)
    }

    private fun pinNew(
        sighting: Sighting,
        now: Long,
    ): Pair<ContactBook, Verdict> {
        if (pinCount >= MAX_PINS) return this to Verdict.Blocked(TrustConflict.BOOK_FULL)
        val pinned = Contact(sighting.accountId, sighting.username, ContactStatus.OK, sighting.devices, null, now, now)
        // The claim wins over what the list suggested for this name.
        val rest = contacts.filterValues { !(it.status == ContactStatus.UNCONFIRMED && it.username == sighting.username) }
        return ContactBook(rest + (pinned.accountId to pinned)) to Verdict.Usable
    }

    private fun seeInList(
        sighting: Sighting,
        now: Long,
    ): ContactBook {
        val existing = contacts[sighting.accountId]
        val known = existing?.takeIf { it.status != ContactStatus.UNCONFIRMED }
        return when {
            known != null -> {
                seeKnownInList(sighting, known, now)
            }

            keyHeldByOther(sighting.devices.keys, sighting.accountId) -> {
                block(sighting, null, TrustConflict.KEY_CONFLICT, now).first
            }

            existing == null && unconfirmedCount >= MAX_UNCONFIRMED -> {
                this
            }

            else -> {
                with(
                    Contact(
                        sighting.accountId,
                        sighting.username,
                        ContactStatus.UNCONFIRMED,
                        sighting.devices,
                        null,
                        existing?.firstSeen ?: now,
                        now,
                    ),
                )
            }
        }
    }

    private fun seeKnownInList(
        sighting: Sighting,
        known: Contact,
        now: Long,
    ): ContactBook {
        val reason =
            if (keyHeldByOther(
                    sighting.devices.keys,
                    sighting.accountId,
                )
            ) {
                TrustConflict.KEY_CONFLICT
            } else {
                conflictWithPins(sighting, known)
            }
        return when {
            reason != null -> block(sighting, known, reason, now).first
            known.status == ContactStatus.GONE -> with(known.copy(status = ContactStatus.OK, lastSeen = now))
            else -> with(known.copy(lastSeen = now))
        }
    }

    private fun dropAbsent(
        listed: Set<String>,
        now: Long,
    ): ContactBook =
        ContactBook(
            contacts.values
                .filterNot { it.accountId !in listed && it.status == ContactStatus.UNCONFIRMED }
                .associate { c ->
                    c.accountId to
                        if (c.accountId !in listed &&
                            c.status == ContactStatus.OK
                        ) {
                            c.copy(status = ContactStatus.GONE, lastSeen = now)
                        } else {
                            c
                        }
                },
        )

    companion object {
        /** Pins are never evicted: at the cap a new account is refused (ADR-0014). */
        const val MAX_PINS = 500
        const val MAX_UNCONFIRMED = 100
    }
}
