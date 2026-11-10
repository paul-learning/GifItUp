package app.gifitup.shared.domain.groups

import app.gifitup.shared.data.crypto.MlsClient
import app.gifitup.shared.data.crypto.MlsErrorCode
import app.gifitup.shared.data.crypto.MlsException
import app.gifitup.shared.data.crypto.WelcomeInfo
import app.gifitup.shared.data.network.ApiError
import app.gifitup.shared.data.network.ApiResult
import app.gifitup.shared.data.network.PendingWelcomeEntry
import app.gifitup.shared.domain.contacts.KeyBytes
import kotlinx.coroutines.CancellationException

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
    private val sink: IncomingSink,
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
            // At the cap the welcome stays pending, unparsed: nothing is lost, and it is joined once a slot is freed.
            existing == null && store.state.groups.size >= SyncStateCodec.MAX_GROUPS -> {
                WelcomeOutcome.Skipped(welcome.groupId)
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
    ): WelcomeOutcome =
        // The join point (our acked seq) is read before the welcome is parsed: nothing is awaited while it is staged.
        when (val state = delivery.status(welcome.groupId)) {
            is ApiResult.Ok -> {
                parseAndJoin(welcome, pins, state.value.ackedSeq)
            }

            // The group no longer lists this device (it was removed, or left, before it joined): this welcome can never be
            // joined, and it must not stop the ones behind it (#280). Only the server's decoded "not found" counts; a proxy's
            // 404 page is a malformed response and still ends the run.
            is ApiResult.Failed -> {
                if (state.error == ApiError.NOT_FOUND) stale(welcome) else halt(SyncOutcome.Unavailable(state.error))
            }
        }

    private suspend fun parseAndJoin(
        welcome: PendingWelcomeEntry,
        pins: ContactPins,
        joinPoint: Long,
    ): WelcomeOutcome {
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
            joined(welcome, info, joinPoint, unverified)
        }
    }

    /**
     * A welcome for a group this device is no longer in. If it still parses, discard it so the KeyPackage's private key is retired
     * (forward secrecy), then acknowledge it; if it does not parse, just acknowledge it. Never a reason to stop the run.
     */
    private suspend fun stale(welcome: PendingWelcomeEntry): WelcomeOutcome {
        // Direct calls, not `crate {}`: that turns every crate failure into a halt (a dead engine), and an unusable stale welcome
        // is an ordinary case (#293 SEC-1). Only a poisoned client ends the run, as in `rejected`.
        try {
            mls.discardWelcome(mls.inspectWelcome(welcome.welcome, welcome.groupId).token)
        } catch (e: MlsException) {
            if (e.code == MlsErrorCode.Poisoned) halt(SyncOutcome.Poisoned(null))
        }
        drop(welcome.id)
        return WelcomeOutcome.Rejected(RejectReason.STALE)
    }

    private suspend fun joined(
        welcome: PendingWelcomeEntry,
        info: WelcomeInfo,
        joinPoint: Long,
        unverified: List<KeyBytes>,
    ): WelcomeOutcome {
        try {
            mls.joinWelcome(info.token)
        } catch (e: MlsException) {
            return rejected(welcome.id, e.code, RejectReason.UNUSABLE)
        }
        announceJoin(welcome.groupId, info, joinPoint)
        store.put(KeyBytes(welcome.groupId), GroupRecord.joinedAt(joinPoint, unverified))
        store.persist()
        drop(welcome.id)
        return WelcomeOutcome.Joined(welcome.groupId)
    }

    /** "You joined": the roster size and the welcome's authenticated sender, told to the sink once the join succeeded. */
    private suspend fun announceJoin(
        groupId: ByteArray,
        info: WelcomeInfo,
        joinPoint: Long,
    ) {
        try {
            sink.membership(MembershipChange(groupId, joinPoint, info.sender.username, emptyList(), emptyList(), info.members.size))
        } catch (e: CancellationException) {
            throw e
        } catch (ignored: Exception) {
            // The notice is only a convenience: a failure here must not undo a join that is done.
        }
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
