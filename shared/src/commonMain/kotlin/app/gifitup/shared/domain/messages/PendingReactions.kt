package app.gifitup.shared.domain.messages

import app.gifitup.shared.domain.contacts.KeyBytes
import app.gifitup.shared.domain.groups.SyncOutcome

/**
 * The reactions that are sealed and not settled yet, by their message reference (ADR-0006 B10a). A reaction has no record
 * of its own: it is applied to its target once the server accepted it (on the answer of a send, of a resume, or of the next
 * send that settles it first), and forgotten when the server refused it or the engine's stored state says it is not the
 * pending message any more. An accepted reaction whose write failed stays until the next reconcile applies it. Memory only,
 * at most [MAX] entries: a process that ends between an unknown answer and the resume loses the local chip of its own
 * reaction (the other members have it). Used under [SendGif]'s lock only.
 */
internal class PendingReactions(
    private val store: MessageStore,
) {
    private val entries = LinkedHashMap<KeyBytes, Entry>()

    class Entry(
        val group: KeyBytes,
        val target: KeyBytes,
        val own: String,
        val emoji: String?,
    )

    fun remember(
        reference: KeyBytes,
        entry: Entry,
    ) {
        entries[reference] = entry
        while (entries.size > MAX) entries.remove(entries.keys.first())
    }

    /** The answer to a reaction: accepted, then it shows on its target; refused, then it is forgotten; anything else waits. */
    suspend fun settle(
        reference: KeyBytes,
        outcome: SyncOutcome,
    ) {
        val pending = entries[reference] ?: return
        when (outcome) {
            is SyncOutcome.Sent -> {
                // Forgotten only once the write is done. A store that fails here throws (the caller swallows it for a settle) and
                // the entry stays: the next reconcile finds this message as the last accepted one and applies it then. A `false`
                // answer (the target is gone, or the message is full) is settled too: there is nothing left to apply it to.
                store.react(pending.group, pending.target, pending.own, pending.emoji)
                entries.remove(reference)
            }

            is SyncOutcome.Rejected -> {
                entries.remove(reference)
            }

            else -> {
                Unit
            }
        }
    }

    /**
     * Reactions have no record to settle, so the engine's stored state decides them: the last accepted message is accepted
     * (applied now if its own answer could not apply it), the pending one stays, any other of this group is forgotten.
     */
    suspend fun reconcile(
        group: KeyBytes,
        pending: KeyBytes?,
        lastSent: KeyBytes?,
        lastSentKnown: Boolean,
    ) {
        entries
            .filter { it.value.group == group && it.key != pending }
            .keys
            .forEach { reference ->
                if (reference == lastSent && lastSentKnown) settle(reference, SyncOutcome.Sent(0)) else entries.remove(reference)
            }
    }

    private companion object {
        const val MAX = 64
    }
}
