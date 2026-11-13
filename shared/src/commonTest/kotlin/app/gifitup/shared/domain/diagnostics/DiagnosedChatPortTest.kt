package app.gifitup.shared.domain.diagnostics

import app.gifitup.shared.data.content.GifReference
import app.gifitup.shared.data.network.ApiEndpoint
import app.gifitup.shared.data.network.ApiError
import app.gifitup.shared.data.network.Session
import app.gifitup.shared.domain.auth.AuthError
import app.gifitup.shared.domain.auth.AuthResult
import app.gifitup.shared.domain.chat.ChatPort
import app.gifitup.shared.domain.contacts.KeyBytes
import app.gifitup.shared.domain.groups.GroupCreation
import app.gifitup.shared.domain.groups.GroupList
import app.gifitup.shared.domain.groups.RejectReason
import app.gifitup.shared.domain.groups.SyncOutcome
import app.gifitup.shared.domain.groups.WelcomeOutcome
import app.gifitup.shared.domain.groups.WelcomesReport
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame

class DiagnosedChatPortTest {
    private val session = Session("d", "t", Long.MAX_VALUE, checkNotNull(ApiEndpoint.forOrigin("https://chat.example.test")))
    private val group = KeyBytes(ByteArray(32))
    private val reference =
        assertNotNull(
            GifReference.of(
                "klipy",
                "funny-cat",
                "https://static.klipy.com/ii/0123456789abcdef0123456789abcdef/ab/cd/funny_cat-1.webp",
                320,
                240,
            ),
        )

    private class Recording : DiagnosticsLog {
        val noted = mutableListOf<Pair<DiagEvent, DiagDetail?>>()

        override fun record(
            event: DiagEvent,
            detail: DiagDetail?,
        ) {
            noted += event to detail
        }

        override suspend fun entries() = emptyList<DiagEntry>()

        override suspend fun clear() = Unit

        override suspend fun stop() = Unit
    }

    /** Answers every call with [answer]; only the sync-like calls and the reads matter here. */
    private class Fake(
        var answer: AuthResult<SyncOutcome>,
        var welcomes: AuthResult<WelcomesReport> = AuthResult.Ok(WelcomesReport(emptyList(), null)),
    ) : ChatPort {
        override suspend fun groups(session: Session): AuthResult<GroupList> = AuthResult.Failed(AuthError.Api(ApiError.NETWORK))

        override suspend fun welcomes(session: Session) = welcomes

        override suspend fun sync(
            session: Session,
            groupId: KeyBytes,
        ) = answer

        override suspend fun create(
            session: Session,
            target: String,
        ): AuthResult<GroupCreation> = AuthResult.Failed(AuthError.Api(ApiError.NOT_FOUND))

        override suspend fun add(
            session: Session,
            groupId: KeyBytes,
            target: String,
        ) = answer

        override suspend fun addToNewGroup(
            session: Session,
            groupId: KeyBytes,
            target: String,
        ) = answer

        override suspend fun sendGif(
            session: Session,
            groupId: KeyBytes,
            reference: GifReference,
            caption: String?,
        ) = answer

        override suspend fun rename(
            session: Session,
            groupId: KeyBytes,
            name: String,
        ) = answer

        override suspend fun react(
            session: Session,
            groupId: KeyBytes,
            target: KeyBytes,
            emoji: String?,
        ) = answer

        override suspend fun resume(
            session: Session,
            groupId: KeyBytes,
        ) = answer

        override suspend fun leave(
            session: Session,
            groupId: KeyBytes,
        ) = answer

        override suspend fun acceptRequest(
            session: Session,
            groupId: KeyBytes,
        ) = answer
    }

    private fun failed(error: ApiError) = AuthResult.Failed(AuthError.Api(error))

    @Test
    fun theResultIsReturnedUnchangedAndFailuresAreNotedWithTheirReason() =
        runTest {
            val log = Recording()
            val inner = Fake(failed(ApiError.RATE_LIMITED))
            val port = DiagnosedChatPort(inner, log)
            assertSame(inner.answer, port.sync(session, group))
            port.groups(session)
            assertEquals<List<Pair<DiagEvent, DiagDetail?>>>(
                listOf(
                    DiagEvent.CALL_FAILED_READ to DiagDetail.API_RATE_LIMITED,
                    DiagEvent.CALL_FAILED_READ to DiagDetail.API_NETWORK,
                ),
                log.noted,
            )
        }

    @Test
    fun syncStatesThatMeanTroubleAreNotedAndSuccessesAreNot() =
        runTest {
            val log = Recording()
            val inner = Fake(AuthResult.Ok(SyncOutcome.CaughtUp(3)))
            val port = DiagnosedChatPort(inner, log)
            port.sync(session, group)
            inner.answer = AuthResult.Ok(SyncOutcome.Sent(1))
            port.sendGif(session, group, reference, null)
            assertEquals(emptyList<Pair<DiagEvent, DiagDetail?>>(), log.noted)
            inner.answer = AuthResult.Ok(SyncOutcome.StorageFailed)
            port.sync(session, group)
            inner.answer = AuthResult.Ok(SyncOutcome.Unavailable(ApiError.SERVER_ERROR))
            port.sendGif(session, group, reference, null)
            assertEquals<List<Pair<DiagEvent, DiagDetail?>>>(
                listOf(DiagEvent.SYNC_NOTE to DiagDetail.SYNC_STORAGE_FAILED, DiagEvent.CALL_FAILED_SEND to DiagDetail.API_SERVER_ERROR),
                log.noted,
            )
        }

    @Test
    fun aSetupFailureAboutAPersonIsNotNotedButASystemOneIs() =
        runTest {
            val log = Recording()
            val inner = Fake(failed(ApiError.NOT_FOUND))
            val port = DiagnosedChatPort(inner, log)
            port.create(session, "someone") // "no such member": would say who the user tried to add
            port.add(session, group, "someone")
            assertEquals(emptyList<Pair<DiagEvent, DiagDetail?>>(), log.noted)
            inner.answer = failed(ApiError.NETWORK)
            port.add(session, group, "someone")
            assertEquals<List<Pair<DiagEvent, DiagDetail?>>>(listOf(DiagEvent.CALL_FAILED_SETUP to DiagDetail.API_NETWORK), log.noted)
        }

    @Test
    fun aSetupFailureThatNamesAPersonIsNeverNotedWhateverItsKind() =
        runTest {
            val log = Recording()
            val inner = Fake(AuthResult.Failed(AuthError.Local.CONTACT_BLOCKED))
            val port = DiagnosedChatPort(inner, log)
            listOf(AuthError.Local.CONTACT_BLOCKED, AuthError.Local.INVALID_USERNAME, AuthError.Local.CONTACTS_UNAVAILABLE).forEach {
                inner.answer = AuthResult.Failed(it)
                port.add(session, group, "someone")
                port.addToNewGroup(session, group, "someone")
                port.sync(session, group)
            }
            assertEquals(emptyList<Pair<DiagEvent, DiagDetail?>>(), log.noted)
            inner.answer = AuthResult.Failed(AuthError.Local.STORAGE_FAILED)
            port.add(session, group, "someone")
            assertEquals<List<Pair<DiagEvent, DiagDetail?>>>(
                listOf(DiagEvent.CALL_FAILED_SETUP to DiagDetail.LOCAL_STORAGE_FAILED),
                log.noted,
            )
        }

    @Test
    fun statesThatWouldMarkAMembershipChangeOrAMessageAreNotNotedAndSetupNotesOnlyTheDeviceOnes() =
        runTest {
            val log = Recording()
            val inner = Fake(AuthResult.Ok(SyncOutcome.Retry(1)))
            val port = DiagnosedChatPort(inner, log)
            listOf(
                SyncOutcome.Retry(1),
                SyncOutcome.CommitPending,
                SyncOutcome.LookupIncomplete,
                SyncOutcome.Stopped(app.gifitup.shared.domain.groups.GroupStatus.ACTIVE),
            ).forEach {
                inner.answer = AuthResult.Ok(it)
                port.sync(session, group)
                port.resume(session, group)
            }
            assertEquals(emptyList<Pair<DiagEvent, DiagDetail?>>(), log.noted)
            // A device state is noted for a read, but not for a setup call (resume posts pending membership commits).
            inner.answer = AuthResult.Ok(SyncOutcome.Busy)
            port.resume(session, group)
            assertEquals(emptyList<Pair<DiagEvent, DiagDetail?>>(), log.noted)
            port.sync(session, group)
            assertEquals<List<Pair<DiagEvent, DiagDetail?>>>(listOf(DiagEvent.SYNC_NOTE to DiagDetail.SYNC_BUSY), log.noted)
            // A failed membership commit on resume is a setup failure: GROUP_FULL says nothing system-wide, so it isn't noted.
            log.noted.clear()
            inner.answer = AuthResult.Ok(SyncOutcome.Rejected(ApiError.GROUP_FULL))
            port.resume(session, group)
            assertEquals(emptyList<Pair<DiagEvent, DiagDetail?>>(), log.noted)
        }

    @Test
    fun onlyStaleAndUnusableWelcomesAreNoted() =
        runTest {
            val log = Recording()
            val outcomes =
                RejectReason.entries.map { WelcomeOutcome.Rejected(it) } + WelcomeOutcome.AlreadyMember(ByteArray(32))
            val port = DiagnosedChatPort(Fake(AuthResult.Ok(SyncOutcome.NotJoined), AuthResult.Ok(WelcomesReport(outcomes, null))), log)
            port.welcomes(session)
            assertEquals<List<Pair<DiagEvent, DiagDetail?>>>(
                listOf(DiagEvent.WELCOME_REJECTED to DiagDetail.WELCOME_STALE, DiagEvent.WELCOME_REJECTED to DiagDetail.WELCOME_UNUSABLE),
                log.noted.sortedBy { it.second?.code },
            )
        }
}
