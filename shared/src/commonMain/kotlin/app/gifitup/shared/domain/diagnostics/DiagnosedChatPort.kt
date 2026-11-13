package app.gifitup.shared.domain.diagnostics

import app.gifitup.shared.data.content.GifReference
import app.gifitup.shared.data.network.ApiError
import app.gifitup.shared.data.network.Session
import app.gifitup.shared.domain.auth.AuthError
import app.gifitup.shared.domain.auth.AuthResult
import app.gifitup.shared.domain.chat.ChatPort
import app.gifitup.shared.domain.contacts.KeyBytes
import app.gifitup.shared.domain.groups.GroupCreation
import app.gifitup.shared.domain.groups.GroupList
import app.gifitup.shared.domain.groups.SyncOutcome
import app.gifitup.shared.domain.groups.WelcomeOutcome
import app.gifitup.shared.domain.groups.WelcomesReport

/** Which kind of call an outcome came from; it decides which events the outcome may become. */
private enum class Kind { READ, SEND, SETUP }

/**
 * The chat use cases with a note in the diagnostics log for what went wrong (the debug log, #154 follow-up). It changes no
 * result: each call returns exactly what [inner] returned, and only closed values of the outcome are looked at (never a
 * group id, a name, a target, a caption or a GIF).
 *
 * What is noted: a failed read or send with the closed reason, the sync states that mean "something is off" (retry, new keys
 * needed, a change waiting, a lookup that didn't finish, busy, device storage, crypto stopped, chat stopped) and rejected welcomes
 * (stale or unusable only: see [app.gifitup.shared.domain.groups.RejectReason.diag]). Chat setup (create, add, leave, accept)
 * is noted only when the failure is about the system and not about a person (the network, the session, the server, a limit,
 * crypto or storage), because an add that failed with "no such member" would say who the user tried to add and when. Successes
 * are not noted at all.
 */
@Suppress("TooManyFunctions") // One function per chat use case, as in the port.
class DiagnosedChatPort(
    private val inner: ChatPort,
    private val log: DiagnosticsLog,
) : ChatPort {
    override suspend fun groups(session: Session): AuthResult<GroupList> = inner.groups(session).also { note(Kind.READ, it) }

    override suspend fun welcomes(session: Session): AuthResult<WelcomesReport> =
        inner.welcomes(session).also { result ->
            note(Kind.READ, result)
            if (result is AuthResult.Ok) {
                result.value.results.forEach { outcome ->
                    if (outcome is WelcomeOutcome.Rejected) outcome.reason.diag()?.let { log.record(DiagEvent.WELCOME_REJECTED, it) }
                }
                result.value.stoppedBy?.let { noteSync(Kind.READ, it) }
            }
        }

    override suspend fun sync(
        session: Session,
        groupId: KeyBytes,
    ): AuthResult<SyncOutcome> = inner.sync(session, groupId).also { noteSyncResult(Kind.READ, it) }

    override suspend fun create(
        session: Session,
        target: String,
    ): AuthResult<GroupCreation> =
        inner.create(session, target).also { result ->
            note(Kind.SETUP, result)
            if (result is AuthResult.Ok) noteSync(Kind.SETUP, result.value.outcome)
        }

    override suspend fun add(
        session: Session,
        groupId: KeyBytes,
        target: String,
    ): AuthResult<SyncOutcome> = inner.add(session, groupId, target).also { noteSyncResult(Kind.SETUP, it) }

    override suspend fun addToNewGroup(
        session: Session,
        groupId: KeyBytes,
        target: String,
    ): AuthResult<SyncOutcome> = inner.addToNewGroup(session, groupId, target).also { noteSyncResult(Kind.SETUP, it) }

    override suspend fun sendGif(
        session: Session,
        groupId: KeyBytes,
        reference: GifReference,
        caption: String?,
    ): AuthResult<SyncOutcome> = inner.sendGif(session, groupId, reference, caption).also { noteSyncResult(Kind.SEND, it) }

    override suspend fun rename(
        session: Session,
        groupId: KeyBytes,
        name: String,
    ): AuthResult<SyncOutcome> = inner.rename(session, groupId, name).also { noteSyncResult(Kind.SEND, it) }

    override suspend fun react(
        session: Session,
        groupId: KeyBytes,
        target: KeyBytes,
        emoji: String?,
    ): AuthResult<SyncOutcome> = inner.react(session, groupId, target, emoji).also { noteSyncResult(Kind.SEND, it) }

    override suspend fun resume(
        session: Session,
        groupId: KeyBytes,
    ): AuthResult<SyncOutcome> = inner.resume(session, groupId).also { noteSyncResult(Kind.SEND, it) }

    override suspend fun leave(
        session: Session,
        groupId: KeyBytes,
    ): AuthResult<SyncOutcome> = inner.leave(session, groupId).also { noteSyncResult(Kind.SETUP, it) }

    override suspend fun acceptRequest(
        session: Session,
        groupId: KeyBytes,
    ): AuthResult<SyncOutcome> = inner.acceptRequest(session, groupId).also { noteSyncResult(Kind.SETUP, it) }

    private fun noteSyncResult(
        kind: Kind,
        result: AuthResult<SyncOutcome>,
    ) {
        note(kind, result)
        if (result is AuthResult.Ok) noteSync(kind, result.value)
    }

    /** The failure of a call itself (the session, the network, the server). */
    private fun note(
        kind: Kind,
        result: AuthResult<*>,
    ) {
        if (result !is AuthResult.Failed) return
        val detail =
            when (val error = result.error) {
                is AuthError.Api -> error.error.takeIf { kind != Kind.SETUP || it.isSystemWide() }?.diag()
                is AuthError.Local -> error.diag()
            } ?: return
        log.record(event(kind), detail)
    }

    /** What a sync outcome says; only the states that mean something is off. */
    private fun noteSync(
        kind: Kind,
        outcome: SyncOutcome,
    ) {
        when (outcome) {
            is SyncOutcome.Unavailable -> noteApi(kind, outcome.error)

            is SyncOutcome.InFlight -> noteApi(kind, outcome.error)

            is SyncOutcome.Rejected -> noteApi(kind, outcome.error)

            is SyncOutcome.PendingFirst -> noteSync(kind, outcome.outcome)

            is SyncOutcome.Retry -> log.record(DiagEvent.SYNC_NOTE, DiagDetail.SYNC_RETRY)

            SyncOutcome.RekeyNeeded -> log.record(DiagEvent.SYNC_NOTE, DiagDetail.SYNC_REKEY_NEEDED)

            SyncOutcome.CommitPending -> log.record(DiagEvent.SYNC_NOTE, DiagDetail.SYNC_COMMIT_PENDING)

            SyncOutcome.LookupIncomplete -> log.record(DiagEvent.SYNC_NOTE, DiagDetail.SYNC_LOOKUP_INCOMPLETE)

            SyncOutcome.Busy -> log.record(DiagEvent.SYNC_NOTE, DiagDetail.SYNC_BUSY)

            SyncOutcome.StorageFailed -> log.record(DiagEvent.SYNC_NOTE, DiagDetail.SYNC_STORAGE_FAILED)

            is SyncOutcome.Poisoned -> log.record(DiagEvent.SYNC_NOTE, DiagDetail.SYNC_POISONED)

            is SyncOutcome.Stopped -> log.record(DiagEvent.SYNC_NOTE, DiagDetail.SYNC_STOPPED)

            // Nothing to note: caught up, sent, not joined (a state, not a failure), the user's own invalid request, too long.
            is SyncOutcome.CaughtUp, is SyncOutcome.Sent, SyncOutcome.NotJoined, SyncOutcome.InvalidRequest, SyncOutcome.TooLong -> Unit
        }
    }

    private fun noteApi(
        kind: Kind,
        error: ApiError,
    ) {
        if (kind == Kind.SETUP && !error.isSystemWide()) return
        log.record(event(kind), error.diag())
    }

    private fun event(kind: Kind) =
        when (kind) {
            Kind.READ -> DiagEvent.CALL_FAILED_READ
            Kind.SEND -> DiagEvent.CALL_FAILED_SEND
            Kind.SETUP -> DiagEvent.CALL_FAILED_SETUP
        }

    /** A failure about the system, not about a person the user tried to reach. */
    private fun ApiError.isSystemWide(): Boolean =
        when (this) {
            ApiError.NETWORK, ApiError.UNAUTHORIZED, ApiError.SERVER_ERROR, ApiError.RATE_LIMITED, ApiError.MALFORMED_RESPONSE,
            ApiError.CLIENT_OUTDATED, ApiError.SERVER_OUTDATED, ApiError.GROUP_LIMIT, ApiError.WELCOME_LIMIT,
            -> true

            else -> false
        }
}
