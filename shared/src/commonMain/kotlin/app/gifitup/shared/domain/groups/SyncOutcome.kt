package app.gifitup.shared.domain.groups

import app.gifitup.shared.data.crypto.MlsErrorCode
import app.gifitup.shared.data.crypto.MlsException
import app.gifitup.shared.data.network.ApiError
import app.gifitup.shared.data.network.ApiResult

/** How one [GroupSyncEngine] call ended (sync, welcomes, send, resume). */
sealed interface SyncOutcome {
    /** Everything the server holds for the group is applied and acknowledged; [applied] messages this time. */
    data class CaughtUp(
        val applied: Int,
    ) : SyncOutcome

    /** The group is not syncing: frozen, read-only or removed (see [GroupStatus]). */
    data class Stopped(
        val status: GroupStatus,
    ) : SyncOutcome

    data object NotJoined : SyncOutcome

    /** The Delivery Service couldn't be used (network, auth, rate limit); nothing was lost, try again later. */
    data class Unavailable(
        val error: ApiError,
    ) : SyncOutcome

    /** The contact book isn't available right now; nothing was staged, nothing froze. */
    data object LookupIncomplete : SyncOutcome

    /** An application message failed to decrypt and will be tried again; after three failures it is skipped for good. */
    data class Retry(
        val seq: Long,
    ) : SyncOutcome

    /** The export bound of the snapshot key is reached: rekey (`MlsClient.rekeyAndExport`), then sync again. */
    data object RekeyNeeded : SyncOutcome

    /**
     * The device's storage failed after the live client had already moved on. Drop this engine and client and restore
     * from storage; whatever was not stored is fetched again.
     */
    data object StorageFailed : SyncOutcome

    /** The message is posted and acknowledged by the server at [seq] (`send`, `resume`). */
    data class Sent(
        val seq: Long,
    ) : SyncOutcome

    /**
     * The message is stored but the server's answer is not known (network, rate limit, full log): it stays pending with
     * its client message id, and `resume` posts the same bytes again. Nothing else is sent in this group meanwhile.
     */
    data class InFlight(
        val error: ApiError,
    ) : SyncOutcome

    /** The server refused the message for good (invalid envelope, too large); nothing is pending any more. */
    data class Rejected(
        val error: ApiError,
    ) : SyncOutcome

    /** The text is longer than one padded block holds (896 bytes). */
    data object TooLong : SyncOutcome

    /** Another send would not fit in the stored sync state; resume or sync first. */
    data object Busy : SyncOutcome

    /** The MLS client is dead (panic or trap). Drop it, restore, and call [GroupSyncEngine.recordFailure] on the new one. */
    data class Poisoned(
        val seq: Long?,
    ) : SyncOutcome
}

/** What happened to one pending welcome. */
sealed interface WelcomeOutcome {
    class Joined(
        val groupId: ByteArray,
    ) : WelcomeOutcome

    /** Already a member (a repeat after a crash between storing and acknowledging). */
    class AlreadyMember(
        val groupId: ByteArray,
    ) : WelcomeOutcome

    /** Dropped: unusable, or it names a contact with a key that isn't pinned. Its KeyPackage is gone either way. */
    data class Rejected(
        val reason: RejectReason,
    ) : WelcomeOutcome

    /**
     * Left pending for now: a group of that id exists here in a stopped state (until the user forgets it), or the device
     * holds 50 groups already (until one is forgotten).
     */
    class Skipped(
        val groupId: ByteArray,
    ) : WelcomeOutcome
}

enum class RejectReason { UNUSABLE, CONTACT_MISMATCH }

/** The results for the welcomes handled, and why the run stopped early, if it did. */
class WelcomesReport(
    val results: List<WelcomeOutcome>,
    val stoppedBy: SyncOutcome?,
)

/** Unwinds out of a sync step with [outcome]; caught only by [GroupSyncEngine]. */
internal class Halt(
    val outcome: SyncOutcome,
) : Exception()

internal fun halt(outcome: SyncOutcome): Nothing = throw Halt(outcome)

/** The value of an answer, or a halt with [SyncOutcome.Unavailable]. */
internal fun <T> answer(result: ApiResult<T>): T =
    when (result) {
        is ApiResult.Ok -> result.value
        is ApiResult.Failed -> halt(SyncOutcome.Unavailable(result.error))
    }

/** A crate call whose only expected failure is a dead client (anything else is a storage-level surprise). */
internal fun <T> crate(call: () -> T): T =
    try {
        call()
    } catch (e: MlsException) {
        halt(if (e.code == MlsErrorCode.Poisoned) SyncOutcome.Poisoned(null) else SyncOutcome.StorageFailed)
    }
