package app.gifitup.shared.domain.groups

import app.gifitup.shared.data.crypto.IncomingMessage
import app.gifitup.shared.data.crypto.LeafInfo
import app.gifitup.shared.data.network.GroupMessageEntry
import app.gifitup.shared.domain.contacts.KeyBytes

/** The engine's decision on a staged commit. */
sealed interface CommitDecision {
    /** Apply it. [unverified] are added keys that share a name with another leaf and are not pinned (SEC-18). */
    class Merge(
        val unverified: List<KeyBytes>,
    ) : CommitDecision

    /** Drop it and stop the group in [status]: a commit can't be skipped. */
    class Discard(
        val status: GroupStatus,
    ) : CommitDecision
}

/**
 * Checks a staged commit against what the server said about it and what we know (ADR-0002, ADR-0016). Pure: the crate has
 * already refused identity changes, other proposal types and limit breaches. The envelope is advisory, so the check is
 * by count and by key and needs no device-to-key lookup:
 * - the commit's epoch and the numbers of added and removed leaves equal the envelope's;
 * - removed leaves are leaves of the roster we hold (a ghost may be removed);
 * - an added leaf for a contact must carry a pinned key (else the commit is held and the contact is blocked, ADR-0014);
 * - an added key for a name that already has another leaf is flagged "new, unverified device" unless it is pinned.
 */
internal object CommitJudge {
    fun judge(
        commit: IncomingMessage.Commit,
        entry: GroupMessageEntry,
        roster: List<LeafInfo>,
        pins: ContactPins,
    ): CommitDecision {
        val rosterKeys = roster.map { KeyBytes(it.signatureKey) }.toSet()
        val envelopeFits =
            entry.epoch == commit.epoch &&
                entry.added.size == commit.added.size &&
                entry.removed.size == commit.removed.size &&
                commit.removed.all { KeyBytes(it.signatureKey) in rosterKeys }
        val checks = commit.added.map { it to pins.check(it.username, it.signatureKey) }
        return when {
            !envelopeFits -> CommitDecision.Discard(GroupStatus.FROZEN_ENVELOPE_MISMATCH)
            checks.any { it.second == PinCheck.MISMATCH } -> CommitDecision.Discard(GroupStatus.FROZEN_CONTACT_MISMATCH)
            else -> CommitDecision.Merge(unverifiedKeys(checks, roster))
        }
    }

    /** Strangers whose name is already used by another leaf (of the roster or of the same commit). */
    fun unverifiedKeys(
        added: List<Pair<LeafInfo, PinCheck>>,
        roster: List<LeafInfo>,
    ): List<KeyBytes> {
        val names = (roster.map { it.username } + added.map { it.first.username }).groupingBy { it }.eachCount()
        return added
            .filter { (leaf, check) -> check == PinCheck.STRANGER && (names[leaf.username] ?: 0) > 1 }
            .map { KeyBytes(it.first.signatureKey) }
    }
}
