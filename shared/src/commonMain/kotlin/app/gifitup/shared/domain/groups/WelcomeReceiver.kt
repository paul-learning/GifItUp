package app.gifitup.shared.domain.groups

import app.gifitup.shared.data.contacts.KeyBytes
import app.gifitup.shared.data.crypto.MlsClient
import app.gifitup.shared.data.crypto.MlsErrorCode
import app.gifitup.shared.data.crypto.MlsException
import app.gifitup.shared.data.network.PendingWelcomeEntry

/**
 * Handles the welcomes waiting for this device (ADR-0016): look up everything that needs the network or the contact book,
 * parse the welcome once, check its members, join, store, and only then acknowledge it. Rejected welcomes are
 * acknowledged too: parsing consumed their KeyPackage, so they can never be joined later.
 */
internal class WelcomeReceiver(
    private val mls: MlsClient,
    private val delivery: DeliveryPort,
    private val pinsSource: ContactPinsSource,
    private val store: SyncStore,
) {
    suspend fun process(results: MutableList<WelcomeOutcome>): SyncOutcome {
        val pins = pinsSource.load() ?: halt(SyncOutcome.LookupIncomplete)
        for (welcome in answer(delivery.pendingWelcomes())) {
            results += outcomeOf(welcome, pins)
        }
        return SyncOutcome.CaughtUp(results.size)
    }

    private suspend fun outcomeOf(
        welcome: PendingWelcomeEntry,
        pins: ContactPins,
    ): WelcomeOutcome {
        val existing = store.find(KeyBytes(welcome.groupId))
        return when {
            existing == null && store.state.groups.size >= SyncStateCodec.MAX_GROUPS -> {
                // Stopped and removed groups count too until the user forgets them; the welcome is dropped, unparsed.
                drop(welcome.id)
                WelcomeOutcome.Rejected(RejectReason.GROUP_LIMIT)
            }

            existing == null -> {
                join(welcome, pins)
            }

            existing.status == GroupStatus.ACTIVE -> {
                WelcomeOutcome.AlreadyMember(welcome.groupId).also { drop(welcome.id) }
            }

            else -> {
                WelcomeOutcome.Skipped(welcome.groupId)
            }
        }
    }

    private suspend fun join(
        welcome: PendingWelcomeEntry,
        pins: ContactPins,
    ): WelcomeOutcome {
        // The join point (our acked seq) is read before the welcome is parsed: nothing is awaited while it is staged.
        val joinPoint = answer(delivery.status(welcome.groupId)).ackedSeq
        val info =
            try {
                mls.inspectWelcome(welcome.welcome, welcome.groupId)
            } catch (e: MlsException) {
                return rejected(welcome.id, e.code, RejectReason.UNUSABLE)
            }
        val checks = (info.members + info.sender).map { it to pins.check(it.username, it.signatureKey) }
        return if (checks.any { it.second == PinCheck.MISMATCH }) {
            crate { mls.discardWelcome(info.token) }
            rejected(welcome.id, null, RejectReason.CONTACT_MISMATCH)
        } else {
            val unverified = CommitJudge.unverifiedKeys(info.members.map { it to pins.check(it.username, it.signatureKey) }, emptyList())
            joined(welcome, info.token, joinPoint, unverified)
        }
    }

    private suspend fun joined(
        welcome: PendingWelcomeEntry,
        token: Int,
        joinPoint: Long,
        unverified: List<KeyBytes>,
    ): WelcomeOutcome {
        try {
            mls.joinWelcome(token)
        } catch (e: MlsException) {
            return rejected(welcome.id, e.code, RejectReason.UNUSABLE)
        }
        store.put(KeyBytes(welcome.groupId), GroupRecord.joinedAt(joinPoint, unverified))
        store.persist()
        drop(welcome.id)
        return WelcomeOutcome.Joined(welcome.groupId)
    }

    private suspend fun rejected(
        welcomeId: String,
        code: MlsErrorCode?,
        reason: RejectReason,
    ): WelcomeOutcome {
        if (code == MlsErrorCode.Poisoned) halt(SyncOutcome.Poisoned(null))
        drop(welcomeId)
        return WelcomeOutcome.Rejected(reason)
    }

    /** Acknowledging only removes the welcome from the server; a failure leaves it pending and harmless. */
    private suspend fun drop(welcomeId: String) {
        delivery.ackWelcome(welcomeId)
    }
}
