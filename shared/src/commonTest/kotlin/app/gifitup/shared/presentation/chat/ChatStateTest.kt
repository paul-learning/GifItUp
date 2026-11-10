package app.gifitup.shared.presentation.chat

import app.gifitup.shared.data.content.GifReference
import app.gifitup.shared.data.network.ApiEndpoint
import app.gifitup.shared.data.network.ApiError
import app.gifitup.shared.data.network.Session
import app.gifitup.shared.domain.auth.AuthError
import app.gifitup.shared.domain.auth.AuthResult
import app.gifitup.shared.domain.chat.ChatKind
import app.gifitup.shared.domain.chat.ChatPort
import app.gifitup.shared.domain.chat.ReadMarks
import app.gifitup.shared.domain.contacts.KeyBytes
import app.gifitup.shared.domain.groups.GroupCreation
import app.gifitup.shared.domain.groups.GroupInfo
import app.gifitup.shared.domain.groups.GroupList
import app.gifitup.shared.domain.groups.GroupStatus
import app.gifitup.shared.domain.groups.RejectReason
import app.gifitup.shared.domain.groups.SyncOutcome
import app.gifitup.shared.domain.groups.WelcomeOutcome
import app.gifitup.shared.domain.groups.WelcomesReport
import app.gifitup.shared.domain.messages.CaptionInput
import app.gifitup.shared.domain.messages.ChatMessage
import app.gifitup.shared.domain.messages.CodePointCounter
import app.gifitup.shared.domain.messages.DeliveryState
import app.gifitup.shared.domain.messages.HistoryAccess
import app.gifitup.shared.domain.messages.HistoryStoreException
import app.gifitup.shared.domain.messages.InMemoryMessageStore
import app.gifitup.shared.domain.messages.MessageBody
import app.gifitup.shared.domain.messages.ResetResult
import app.gifitup.shared.domain.session.SessionHolder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The chat list and the conversation (#25) over a scripted group engine and the real in-memory message store. */
@Suppress("LargeClass") // One screen's table of cases over a shared fake group engine.
class ChatStateTest {
    private val store = InMemoryMessageStore()
    private val marks = ReadMarks()
    private val url = "https://static.klipy.com/ii/0123456789abcdef0123456789abcdef/ab/cd/funny_cat-1.webp"
    private val reference = assertNotNull(GifReference.of("klipy", "funny-cat", url, 320, 240))
    private var now = 1_000L

    private fun id(n: Int) = KeyBytes(byteArrayOf(n.toByte()))

    private fun group(
        n: Int,
        vararg others: String,
        status: GroupStatus = GroupStatus.ACTIVE,
    ) = GroupInfo(id(n), status, others.toList())

    @Suppress("LongParameterList") // A builder of test messages.
    private fun gif(
        group: Int,
        ref: Int,
        sender: String?,
        at: Long,
        caption: String? = null,
        state: DeliveryState? = null,
    ) = ChatMessage(id(group), id(100 + ref), sender, null, MessageBody.Gif(reference, caption), state, at)

    private inner class FakeChat : ChatPort {
        var groups = listOf<GroupInfo>()
        var failWith: AuthError? = null
        var syncOutcome: SyncOutcome = SyncOutcome.CaughtUp(0)
        var sendOutcome: SyncOutcome = SyncOutcome.Sent(5)
        var onSync: suspend (KeyBytes) -> Unit = {}
        var beforeSend: suspend () -> Unit = {}
        val synced = mutableListOf<KeyBytes>()
        val sent = mutableListOf<Pair<GifReference, String?>>()
        var welcomed = 0
        var welcomeResults: List<WelcomeOutcome> = emptyList()
        var welcomesStoppedBy: SyncOutcome? = null
        var resumed = 0

        private fun <T> answer(value: T): AuthResult<T> = failWith?.let { AuthResult.Failed(it) } ?: AuthResult.Ok(value)

        override suspend fun groups(session: Session) = answer(GroupList("me_user", groups))

        override suspend fun welcomes(session: Session): AuthResult<WelcomesReport> {
            welcomed++
            return answer(WelcomesReport(welcomeResults, welcomesStoppedBy))
        }

        override suspend fun sync(
            session: Session,
            groupId: KeyBytes,
        ): AuthResult<SyncOutcome> {
            synced += groupId
            onSync(groupId)
            return answer(syncOutcome)
        }

        override suspend fun create(
            session: Session,
            target: String,
        ): AuthResult<GroupCreation> = answer(GroupCreation(null, SyncOutcome.InvalidRequest))

        override suspend fun add(
            session: Session,
            groupId: KeyBytes,
            target: String,
        ) = answer<SyncOutcome>(SyncOutcome.InvalidRequest)

        override suspend fun addToNewGroup(
            session: Session,
            groupId: KeyBytes,
            target: String,
        ) = answer<SyncOutcome>(SyncOutcome.InvalidRequest)

        override suspend fun rename(
            session: Session,
            groupId: KeyBytes,
            name: String,
        ) = answer<SyncOutcome>(SyncOutcome.InvalidRequest)

        override suspend fun sendGif(
            session: Session,
            groupId: KeyBytes,
            reference: GifReference,
            caption: String?,
        ): AuthResult<SyncOutcome> {
            beforeSend()
            sent += reference to caption
            if (sendOutcome !is SyncOutcome.InvalidRequest) {
                store.insert(gif(groupId.bytes[0].toInt(), 900 + sent.size, null, now, caption, DeliveryState.SENDING))
            }
            return answer(sendOutcome)
        }

        val reacted = mutableListOf<Pair<KeyBytes, String?>>()
        var reactOutcome: SyncOutcome = SyncOutcome.Sent(6)

        override suspend fun react(
            session: Session,
            groupId: KeyBytes,
            target: KeyBytes,
            emoji: String?,
        ): AuthResult<SyncOutcome> {
            reacted += target to emoji
            return answer(reactOutcome)
        }

        val left = mutableListOf<KeyBytes>()
        val accepted = mutableListOf<KeyBytes>()

        override suspend fun leave(
            session: Session,
            groupId: KeyBytes,
        ): AuthResult<SyncOutcome> {
            left += groupId
            return answer(SyncOutcome.CaughtUp(0))
        }

        override suspend fun acceptRequest(
            session: Session,
            groupId: KeyBytes,
        ): AuthResult<SyncOutcome> {
            accepted += groupId
            return answer(SyncOutcome.CaughtUp(0))
        }

        override suspend fun resume(
            session: Session,
            groupId: KeyBytes,
        ): AuthResult<SyncOutcome> {
            resumed++
            return answer(sendOutcome)
        }
    }

    private val chat = FakeChat()
    private val session = Session("d", "t", Long.MAX_VALUE, checkNotNull(ApiEndpoint.forOrigin("https://chat.example.test")))

    private fun TestScope.sessions() = SessionHolder({ AuthResult.Ok(session) }, this) { 0L }

    private fun TestScope.list() = ChatListStateHolder(this, sessions(), chat, store, marks)

    private fun TestScope.conversation(info: GroupInfo) =
        ConversationStateHolder(this, sessions(), chat, store, marks, info, CaptionInput(CodePointCounter))

    @Test
    fun theFilterIsKeptWhenTheListIsLeftAndOpenedAgain() =
        runTest {
            val holder = list()
            holder.showOnly(ChatFilter.GROUPS)
            holder.leave()
            assertEquals(ChatFilter.GROUPS, holder.state.value.filter)
        }

    @Test
    fun theListIsShownFromThisDeviceBeforeTheNetworkStepsOfARefreshFinish() =
        runTest {
            chat.groups = listOf(group(1, "bob"))
            store.insert(gif(1, 1, "bob", at = 100, caption = "hi"))
            val gate = CompletableDeferred<Unit>()
            chat.onSync = { gate.await() }
            val holder = list()
            holder.refresh()
            advanceUntilIdle()
            assertEquals(1, chat.synced.size, "the refresh is waiting on the network")
            assertTrue(holder.state.value.refreshing)
            assertTrue(holder.state.value.loaded, "the list is on screen while the refresh runs")
            assertEquals(
                1,
                holder.state.value.conversations
                    .size,
            )
            gate.complete(Unit)
            advanceUntilIdle()
            assertFalse(holder.state.value.refreshing)
            assertEquals(
                1,
                holder.state.value.conversations
                    .size,
            )
        }

    @Test
    fun theListShowsTheNewestActivityFirstWithPreviewAndUnreadCount() =
        runTest {
            chat.groups = listOf(group(1, "bob"), group(2, "carol"), group(3, "dave"))
            store.insert(gif(1, 1, "bob", at = 100, caption = "old"))
            store.insert(gif(2, 2, "carol", at = 300, caption = "newest"))
            store.insert(gif(2, 3, "carol", at = 301))
            store.insert(gif(2, 4, null, at = 302, caption = "mine", state = DeliveryState.SENT))
            val holder = list()
            holder.refresh()
            advanceUntilIdle()
            val rows = holder.state.value.conversations
            assertEquals(listOf(id(2), id(1), id(3)), rows.map { it.id }, "the group without messages is last")
            val newest = rows[0]
            assertEquals(2, newest.unread, "two received messages, one sent by us")
            assertEquals(302L, newest.lastAt)
            assertEquals(true, assertIs<Preview.Gif>(newest.preview).mine)
            assertEquals("mine", assertIs<Preview.Gif>(newest.preview).caption)
            assertIs<Preview.Nothing>(rows[2].preview)
            assertEquals(listOf("carol"), assertIs<ConversationTitle.Members>(newest.title).names)
            assertTrue(holder.state.value.loaded)
            assertFalse(holder.state.value.refreshing)
        }

    @Test
    fun aRefreshJoinsNewGroupsAndReadsEveryActiveGroupOnly() =
        runTest {
            chat.groups =
                listOf(
                    group(1, "bob"),
                    group(2, "carol", status = GroupStatus.FROZEN_GAP),
                    group(3, "dave", status = GroupStatus.REMOVED),
                    group(4, "erin", status = GroupStatus.READ_ONLY_ROLLBACK),
                )
            val holder = list()
            holder.refresh()
            advanceUntilIdle()
            assertEquals(1, chat.welcomed)
            assertEquals(listOf(id(1)), chat.synced, "stopped, read-only and removed groups are not read")
            val problems =
                holder.state.value.conversations
                    .associate { it.id to it.problem }
            assertNull(problems[id(1)])
            assertEquals(ConversationProblem.STOPPED, problems[id(2)])
            assertEquals(ConversationProblem.GONE, problems[id(3)])
            assertEquals(ConversationProblem.READ_ONLY, problems[id(4)])
        }

    @Test
    fun aSettledNameMakesAChatAGroupEvenWithOneOtherMemberAndASendingNameDoesNot() =
        runTest {
            chat.groups = listOf(group(1, "bob"), group(2, "carol", "dave"), group(3, "erin"))
            for (g in 1..2) store.insert(ChatMessage(id(g), id(200 + g), "bob", 1, MessageBody.GroupMeta("Weekend"), null, 10))
            // Our own name that is still sending or failed is not the chat's name yet (#217).
            store.insert(ChatMessage(id(3), id(203), null, null, MessageBody.GroupMeta("Mine"), DeliveryState.SENDING, 10))
            val holder = list()
            holder.refresh()
            advanceUntilIdle()
            val rows =
                holder.state.value.conversations
                    .associateBy { it.id }
            assertEquals("Weekend", assertIs<ConversationTitle.Named>(rows.getValue(id(1)).title).name, "a named group of one")
            assertEquals("Weekend", assertIs<ConversationTitle.Named>(rows.getValue(id(2)).title).name)
            assertIs<ConversationTitle.Members>(rows.getValue(id(3)).title, "a name still sending does not count")
            assertEquals(ChatKind.GROUP, rows.getValue(id(1)).kind)
            assertEquals(ChatKind.DIRECT, rows.getValue(id(3)).kind)
            val titles = rows.mapValues { it.value.title }
            assertEquals("Weekend", assertIs<ConversationTitle.Named>(titles[id(2)]).name)
            assertEquals(
                "Weekend",
                assertIs<Preview.Renamed>(
                    holder.state.value.conversations
                        .first { it.id == id(1) }
                        .preview,
                ).name,
            )
        }

    @Test
    fun aWelcomeStepThatStoppedIsNamedInsteadOfDropped() =
        runTest {
            chat.groups = listOf(group(1, "bob"))
            val holder = list()
            val cases =
                mapOf<SyncOutcome?, ChatProblem?>(
                    null to null,
                    SyncOutcome.LookupIncomplete to ChatProblem.FAILED,
                    SyncOutcome.Unavailable(ApiError.NETWORK) to ChatProblem.OFFLINE,
                    SyncOutcome.Unavailable(ApiError.RATE_LIMITED) to ChatProblem.THROTTLED,
                    SyncOutcome.Unavailable(ApiError.UNAUTHORIZED) to ChatProblem.NOT_SIGNED_IN,
                )
            for ((stopped, problem) in cases) {
                chat.welcomesStoppedBy = stopped
                holder.refresh()
                advanceUntilIdle()
                assertEquals(problem, holder.state.value.problem, stopped.toString())
            }
        }

    @Test
    fun aGroupSyncThatTheServerSlowedDownIsNamedAsThrottling() =
        runTest {
            chat.groups = listOf(group(1, "bob"))
            val holder = list()
            chat.syncOutcome = SyncOutcome.Unavailable(ApiError.RATE_LIMITED)
            holder.refresh()
            advanceUntilIdle()
            assertEquals(ChatProblem.THROTTLED, holder.state.value.problem)
            chat.failWith = AuthError.Api(ApiError.RATE_LIMITED)
            holder.refresh()
            advanceUntilIdle()
            assertEquals(ChatProblem.THROTTLED, holder.state.value.problem)
        }

    @Test
    fun aFailedRefreshStillShowsWhatIsKnownAndNamesTheProblem() =
        runTest {
            chat.groups = listOf(group(1, "bob"))
            store.insert(gif(1, 1, "bob", at = 5))
            val holder = list()
            chat.failWith = AuthError.Api(ApiError.NETWORK)
            holder.refresh()
            advanceUntilIdle()
            assertEquals(ChatProblem.OFFLINE, holder.state.value.problem)
            chat.failWith = AuthError.Local.NOT_REGISTERED
            holder.refresh()
            advanceUntilIdle()
            assertEquals(ChatProblem.NOT_SIGNED_IN, holder.state.value.problem)
            chat.failWith = null
            chat.syncOutcome = SyncOutcome.Retry(3)
            holder.refresh()
            advanceUntilIdle()
            assertEquals(ChatProblem.FAILED, holder.state.value.problem, "a message that could not be read")
            assertEquals(1, holder.state.value.conversations.size)
            chat.syncOutcome = SyncOutcome.CaughtUp(1)
            holder.refresh()
            advanceUntilIdle()
            assertNull(holder.state.value.problem)
        }

    @Test
    fun aHintRefreshReadsOnlyTheNamedGroupsAndJoinsOnlyWhenAsked() =
        runTest {
            chat.groups = listOf(group(1, "bob"), group(2, "carol"), group(3, "dave"))
            val holder = list()
            holder.refreshOnly(setOf(id(2)))
            advanceUntilIdle()
            assertTrue(chat.synced.isEmpty() && holder.state.value == ChatListState(), "a list nobody shows builds nothing for a hint")
            holder.refresh() // the screen's own first refresh makes the list visible
            advanceUntilIdle()
            chat.synced.clear()
            chat.welcomed = 0
            holder.refreshOnly(setOf(id(2)))
            advanceUntilIdle()
            assertEquals(listOf(id(2)), chat.synced)
            assertEquals(0, chat.welcomed, "no welcome check unless the hint was one")
            holder.refreshOnly(setOf(id(3)), welcomes = true)
            advanceUntilIdle()
            assertEquals(listOf(id(2), id(3)), chat.synced)
            assertEquals(1, chat.welcomed)
            holder.refreshOnly(emptySet())
            advanceUntilIdle()
            assertEquals(listOf(id(2), id(3)), chat.synced, "an empty set only rebuilds the list from the stored history")
            assertEquals(3, holder.state.value.conversations.size)
        }

    @Test
    fun aHintThatComesWhileARefreshRunsIsKeptAndRunsAfterIt() =
        runTest {
            chat.groups = listOf(group(1, "bob"), group(2, "carol"), group(3, "dave"))
            val holder = list()
            holder.refresh()
            holder.refreshOnly(setOf(id(2)))
            holder.refreshOnly(setOf(id(3)))
            advanceUntilIdle()
            // The full refresh read all three; the merged hints follow it as one refresh of the two named groups.
            assertEquals(listOf(id(1), id(2), id(3), id(2), id(3)), chat.synced)
        }

    @Test
    fun onlyNewsThatTheFallbackTimerFindsIsASignOfAQuietSocket() =
        runTest {
            chat.groups = listOf(group(1, "bob"))
            chat.syncOutcome = SyncOutcome.CaughtUp(2)
            val holder = conversation(chat.groups.single())
            var quiet = 0
            holder.onUnannouncedNews = { quiet++ }
            holder.open()
            advanceUntilIdle()
            holder.refreshAnnounced()
            advanceUntilIdle()
            assertEquals(0, quiet, "opening and a hint's read are not signs of a quiet socket")
            holder.poll()
            advanceUntilIdle()
            assertEquals(1, quiet, "the timer found messages no hint announced")
            chat.syncOutcome = SyncOutcome.CaughtUp(0)
            holder.poll()
            advanceUntilIdle()
            assertEquals(1, quiet, "a timer read that finds nothing says nothing")
        }

    @Test
    fun aHintThatArrivesDuringATimerReadMakesItsNewsAnnouncedAndIsNotDropped() =
        runTest {
            chat.groups = listOf(group(1, "bob"))
            chat.syncOutcome = SyncOutcome.CaughtUp(1)
            val holder = conversation(chat.groups.single())
            var quiet = 0
            holder.onUnannouncedNews = { quiet++ }
            holder.open()
            advanceUntilIdle()
            val before = chat.synced.size
            chat.onSync = { if (chat.synced.size == before + 1) holder.refreshAnnounced() } // the hint comes while the read runs
            holder.poll()
            advanceUntilIdle()
            assertEquals(0, quiet, "the hint announced that news")
            assertEquals(before + 2, chat.synced.size, "the hint's own read followed")
            chat.onSync = {}
            holder.poll()
            advanceUntilIdle()
            assertEquals(1, quiet, "after the hint's read, a timer read that finds news counts again")
        }

    @Test
    fun aFailedTimerReadLeavesNothingBehindForTheNextRead() =
        runTest {
            chat.groups = listOf(group(1, "bob"))
            val holder = conversation(chat.groups.single())
            var quiet = 0
            holder.onUnannouncedNews = { quiet++ }
            holder.open()
            advanceUntilIdle()
            chat.failWith = AuthError.Local.NOT_REGISTERED
            holder.poll()
            advanceUntilIdle()
            chat.failWith = null
            chat.syncOutcome = SyncOutcome.CaughtUp(3)
            holder.refresh() // a read of another kind (a retry, a reset) is not the timer
            advanceUntilIdle()
            assertEquals(0, quiet)
        }

    @Test
    fun oneRefreshRunsAtATimeAndLeavingDropsTheState() =
        runTest {
            chat.groups = listOf(group(1, "bob"))
            val holder = list()
            holder.refresh()
            holder.refresh()
            advanceUntilIdle()
            assertEquals(1, chat.synced.size, "the second call while one was running did nothing")
            holder.leave()
            assertEquals(ChatListState(), holder.state.value)
        }

    @Test
    fun theMembersOfAConversationAreItsOtherUsernamesAndSurviveLeaving() =
        runTest {
            val holder = conversation(group(2, "bob", "cy"))
            assertEquals(listOf("bob", "cy"), holder.state.value.members)
            holder.open()
            advanceUntilIdle()
            holder.leave()
            assertEquals(listOf("bob", "cy"), holder.state.value.members)
            assertEquals(emptyList(), conversation(group(3, status = GroupStatus.REMOVED)).state.value.members)
        }

    @Test
    fun aConversationWhoseLogTheServerSlowedDownSaysSo() =
        runTest {
            val holder = conversation(group(1, "bob"))
            chat.syncOutcome = SyncOutcome.Unavailable(ApiError.RATE_LIMITED)
            holder.open()
            advanceUntilIdle()
            assertEquals(ChatProblem.THROTTLED, holder.state.value.problem)
            chat.syncOutcome = SyncOutcome.LookupIncomplete
            holder.refresh()
            advanceUntilIdle()
            assertEquals(ChatProblem.FAILED, holder.state.value.problem)
        }

    @Test
    fun openingAConversationShowsTheStoredMessagesAtOnceReadsTheLogAndMarksItRead() =
        runTest {
            val info = group(1, "bob")
            store.insert(gif(1, 1, "bob", at = 100))
            assertEquals(1, marks.unread(info.id, store.messages(info.id)), "unread before it was ever shown")
            chat.onSync = { store.insert(gif(1, 2, "bob", at = 2_000, caption = "new")) }
            val holder = conversation(info)
            holder.open()
            advanceUntilIdle()
            val state = holder.state.value
            assertEquals(listOf("bob"), state.members, "the other members, from the first frame to the last")
            assertEquals(2, state.messages.size)
            assertEquals("new", assertIs<MessageBody.Gif>(state.messages.last().body).caption)
            assertTrue(state.loaded)
            assertNull(state.problem)
            assertEquals(0, marks.unread(info.id, store.messages(info.id)), "both were on screen")
            store.insert(gif(1, 3, "bob", at = 2_000))
            assertEquals(1, marks.unread(info.id, store.messages(info.id)), "a message of the same second as the last view is still unread")
            holder.refresh()
            advanceUntilIdle()
            assertEquals(0, marks.unread(info.id, store.messages(info.id)))
        }

    @Test
    fun aFailedGroupsCallKeepsTheRowsAlreadyShown() =
        runTest {
            chat.groups = listOf(group(1, "bob"))
            store.insert(gif(1, 1, "bob", at = 5))
            val holder = list()
            holder.refresh()
            advanceUntilIdle()
            assertEquals(1, holder.state.value.conversations.size)
            chat.failWith = AuthError.Api(ApiError.NETWORK)
            holder.refresh()
            advanceUntilIdle()
            assertEquals(ChatProblem.OFFLINE, holder.state.value.problem)
            assertEquals(1, holder.state.value.conversations.size, "the list is not blanked by a failed refresh")
        }

    @Test
    fun aRefreshAfterLeavingIsNotCutShortByTheOldOne() =
        runTest {
            chat.groups = listOf(group(1, "bob"))
            val all = listOf(CompletableDeferred<Unit>(), CompletableDeferred<Unit>())
            val gates = all.toMutableList()
            chat.onSync = { gates.removeAt(0).await() }
            val holder = list()
            holder.refresh()
            advanceUntilIdle()
            holder.leave()
            holder.refresh()
            advanceUntilIdle()
            assertEquals(2, chat.synced.size, "the second refresh started although the first was still waiting")
            holder.refresh()
            advanceUntilIdle()
            assertEquals(2, chat.synced.size, "a third while the second runs does nothing")
            all.forEach { it.complete(Unit) }
            advanceUntilIdle()
        }

    @Test
    fun aGifSentWhileTheLogIsBeingReadIsSentNotDropped() =
        runTest {
            val info = group(1, "bob")
            val gate = CompletableDeferred<Unit>()
            chat.onSync = { gate.await() }
            val holder = conversation(info)
            holder.open()
            advanceUntilIdle()
            assertTrue(holder.state.value.refreshing, "the read is in flight")
            holder.send(reference, "now")
            advanceUntilIdle()
            assertEquals(listOf<Pair<GifReference, String?>>(reference to "now"), chat.sent)
            gate.complete(Unit)
            advanceUntilIdle()
            assertEquals(1, chat.sent.size)
            assertFalse(holder.state.value.refreshing)
        }

    @Test
    fun aBadOrTooLongCaptionSendsNothingAndAGoodOneIsSentTrimmed() =
        runTest {
            val holder = conversation(group(1, "bob"))
            holder.send(reference, "a".repeat(81))
            holder.send(reference, "one\ntwo")
            advanceUntilIdle()
            assertTrue(chat.sent.isEmpty())
            assertEquals(SendProblem.CAPTION, holder.state.value.sendProblem)
            assertFalse(holder.state.value.sending)
            holder.send(reference, "  hi  ")
            advanceUntilIdle()
            assertEquals(listOf<Pair<GifReference, String?>>(reference to "hi"), chat.sent)
            assertNull(holder.state.value.sendProblem)
        }

    @Test
    fun aSecondSendWhileOneIsRunningSaysSoInsteadOfBeingDropped() =
        runTest {
            val holder = conversation(group(1, "bob"))
            val gate = CompletableDeferred<Unit>()
            chat.beforeSend = { gate.await() }
            holder.send(reference, "one")
            advanceUntilIdle()
            assertTrue(holder.state.value.sending)
            holder.send(reference, "two")
            assertEquals(SendProblem.IN_PROGRESS, holder.state.value.sendProblem)
            gate.complete(Unit)
            advanceUntilIdle()
            assertEquals(1, chat.sent.size)
            assertFalse(holder.state.value.sending)
        }

    @Test
    fun aStoppedConversationIsNotReadAndNothingIsSentFromIt() =
        runTest {
            for (status in listOf(GroupStatus.FROZEN_GAP, GroupStatus.READ_ONLY_ROLLBACK, GroupStatus.REMOVED)) {
                val holder = conversation(group(1, "bob", status = status))
                holder.open()
                holder.send(reference, null)
                advanceUntilIdle()
                assertTrue(chat.synced.isEmpty() && chat.sent.isEmpty(), "$status")
                assertFalse(holder.state.value.canSend)
                assertEquals(SendProblem.STOPPED, holder.state.value.sendProblem, "$status")
                holder.resend()
                assertEquals(SendProblem.STOPPED, holder.state.value.sendProblem)
            }
        }

    @Test
    fun aRequestIsNotReadNotShownAndNothingIsSentFromIt() =
        runTest {
            val request = GroupInfo(id(1), GroupStatus.ACTIVE, listOf("stranger"), requestedBy = "stranger")
            val holder = conversation(request)
            assertEquals(ConversationProblem.REQUEST, holder.state.value.conversationProblem)
            holder.open()
            holder.refresh()
            holder.send(reference, null)
            advanceUntilIdle()
            assertTrue(chat.synced.isEmpty() && chat.sent.isEmpty(), "nothing is read from or sent into a request")
            assertFalse(holder.state.value.canSend)
            assertTrue(
                holder.state.value.messages
                    .isEmpty(),
            )
        }

    @Test
    fun aRequestIsAListRowWithTheRequestProblemAndNoPreviewOrUnreadCount() =
        runTest {
            chat.groups = listOf(GroupInfo(id(1), GroupStatus.ACTIVE, listOf("stranger"), requestedBy = "stranger"), group(2, "bob"))
            val holder = list()
            holder.refresh()
            advanceUntilIdle()
            assertEquals(listOf(id(2)), chat.synced, "only the normal chat is read")
            val rows =
                holder.state.value.conversations
                    .associateBy { it.id }
            val row = rows.getValue(id(1))
            assertEquals(ConversationProblem.REQUEST, row.problem)
            assertEquals(0, row.unread)
            assertEquals(null, row.lastAt)
            assertEquals("stranger", row.info.requestedBy)
        }

    @Test
    fun acceptingOrDecliningARequestCallsThePortAndRebuildsTheListAndAFailureIsSaid() =
        runTest {
            val request = GroupInfo(id(1), GroupStatus.ACTIVE, listOf("stranger"), requestedBy = "stranger")
            chat.groups = listOf(request, group(2, "bob"))
            val holder = list()
            holder.refresh()
            advanceUntilIdle()
            assertEquals(
                listOf(id(1)),
                holder.state.value.requests
                    .map { it.id },
            )
            assertEquals(
                listOf(id(2)),
                holder.state.value.visible
                    .map { it.id },
                "a request is not in the filtered list",
            )
            holder.acceptRequest(id(1))
            advanceUntilIdle()
            assertEquals(listOf(id(1)), chat.accepted)
            assertFalse(holder.state.value.actionFailed)
            chat.failWith = AuthError.Api(ApiError.NETWORK)
            holder.leaveChat(id(1))
            advanceUntilIdle()
            assertEquals(listOf(id(1)), chat.left)
            assertTrue(holder.state.value.actionFailed, "a failure is said, and nothing is dropped from the list")
            assertEquals(2, holder.state.value.conversations.size)
        }

    @Test
    fun welcomesThatWaitForASlotOrWereDroppedAtTheRequestCapAreShownNotSilent() =
        runTest {
            chat.groups = listOf(group(1, "bob"))
            chat.welcomeResults =
                listOf(
                    WelcomeOutcome.Skipped(byteArrayOf(9)),
                    WelcomeOutcome.Rejected(RejectReason.REQUEST_LIMIT),
                    WelcomeOutcome.Rejected(RejectReason.REQUEST_LIMIT),
                    WelcomeOutcome.Rejected(RejectReason.STALE),
                )
            val holder = list()
            holder.refresh()
            advanceUntilIdle()
            assertEquals(1, holder.state.value.welcomesWaiting)
            assertEquals(2, holder.state.value.requestsDropped)
        }

    @Test
    fun aSentGifAppearsAndTheOutcomeDecidesWhatIsSaid() =
        runTest {
            val holder = conversation(group(1, "bob"))
            holder.send(reference, "hi")
            advanceUntilIdle()
            assertEquals(listOf<Pair<GifReference, String?>>(reference to "hi"), chat.sent)
            assertEquals(1, holder.state.value.messages.size)
            assertNull(holder.state.value.sendProblem)
            assertFalse(holder.state.value.sending)
            val cases =
                mapOf(
                    SyncOutcome.InvalidRequest to SendProblem.CAPTION,
                    SyncOutcome.PendingFirst(SyncOutcome.InFlight(ApiError.NETWORK)) to SendProblem.EARLIER_FIRST,
                    SyncOutcome.Busy to SendProblem.SYNC_FIRST,
                    SyncOutcome.CommitPending to SendProblem.SYNC_FIRST,
                    SyncOutcome.NotJoined to SendProblem.STOPPED,
                    SyncOutcome.Unavailable(ApiError.NETWORK) to SendProblem.OFFLINE,
                    SyncOutcome.Unavailable(ApiError.RATE_LIMITED) to SendProblem.THROTTLED,
                    SyncOutcome.InFlight(ApiError.RATE_LIMITED) to SendProblem.THROTTLED,
                    SyncOutcome.Rejected(ApiError.BAD_REQUEST) to SendProblem.REFUSED,
                    SyncOutcome.StorageFailed to SendProblem.FAILED,
                )
            for ((outcome, problem) in cases) {
                chat.sendOutcome = outcome
                holder.send(reference, null)
                advanceUntilIdle()
                assertEquals(problem, holder.state.value.sendProblem, outcome.toString())
            }
            chat.sendOutcome = SyncOutcome.InFlight(ApiError.NETWORK)
            holder.send(reference, null)
            advanceUntilIdle()
            assertNull(holder.state.value.sendProblem, "an unknown answer is shown by the message's own state")
            assertTrue(holder.state.value.hasUnsettled)
        }

    @Test
    fun sendingFailsClosedWhenSignedOutAndResendUsesTheSameSeam() =
        runTest {
            val holder = conversation(group(1, "bob"))
            chat.failWith = AuthError.Api(ApiError.NETWORK)
            holder.send(reference, null)
            advanceUntilIdle()
            assertEquals(SendProblem.OFFLINE, holder.state.value.sendProblem)
            chat.failWith = AuthError.Local.CRYPTO
            holder.send(reference, null)
            advanceUntilIdle()
            assertEquals(SendProblem.FAILED, holder.state.value.sendProblem)
            chat.failWith = null
            chat.sendOutcome = SyncOutcome.Sent(9)
            holder.resend()
            advanceUntilIdle()
            assertNull(holder.state.value.sendProblem)
        }

    /** The real in-memory store behind a history that can be made to fail like the durable one (ADR-0017). */
    private inner class FlakyHistory : HistoryAccess by store {
        var broken: HistoryStoreException.Kind? = null
        var unreadableRecords = 0
        var retries = 0

        override suspend fun usable(): Boolean = broken == null

        var resettable = false
        var resetResult = ResetResult.DONE
        var resets = 0

        override suspend fun canReset(): Boolean = resettable && broken == HistoryStoreException.Kind.KEY

        override suspend fun reset(): ResetResult {
            resets++
            if (resetResult == ResetResult.DONE) broken = null
            return resetResult
        }

        override suspend fun messages(groupId: KeyBytes): List<ChatMessage> =
            broken?.let { throw HistoryStoreException(it) } ?: store.messages(groupId)

        override suspend fun unreadable(groupId: KeyBytes): Int = unreadableRecords

        override suspend fun retry() {
            retries++
            broken = null
        }
    }

    @Test
    fun aBrokenHistoryMarksTheRowsAndNamesTheProblemInsteadOfShowingNothingOrUnreadZero() =
        runTest {
            chat.groups = listOf(group(1, "bob"))
            store.insert(gif(1, 1, "bob", at = 5))
            val history = FlakyHistory().also { it.broken = HistoryStoreException.Kind.KEY }
            val holder = ChatListStateHolder(this, sessions(), chat, history, marks)
            holder.refresh()
            advanceUntilIdle()
            val row =
                holder.state.value.conversations
                    .single()
            assertTrue(row.historyProblem)
            assertEquals(0, row.unread)
            assertEquals(ChatProblem.HISTORY, holder.state.value.problem)
            holder.retryHistory()
            advanceUntilIdle()
            assertEquals(1, history.retries)
            assertFalse(
                holder.state.value.conversations
                    .single()
                    .historyProblem,
            )
            assertNull(holder.state.value.problem)
            assertEquals(
                1,
                holder.state.value.conversations
                    .single()
                    .unread,
            )
        }

    @Test
    fun aConversationWithABrokenHistoryShowsNothingFromMemoryDoesNotReadTheLogAndSendsNothing() =
        runTest {
            val info = group(1, "bob")
            store.insert(gif(1, 1, "bob", at = 5))
            val history = FlakyHistory().also { it.broken = HistoryStoreException.Kind.STORAGE }
            val holder = ConversationStateHolder(this, sessions(), chat, history, marks, info, CaptionInput(CodePointCounter))
            holder.open()
            advanceUntilIdle()
            val state = holder.state.value
            assertEquals(ChatProblem.HISTORY, state.problem)
            assertTrue(state.messages.isEmpty(), "nothing is shown from memory")
            assertFalse(state.canSend)
            assertTrue(chat.synced.isEmpty(), "the log is not read into a broken history")
            holder.send(reference, null)
            advanceUntilIdle()
            assertEquals(SendProblem.HISTORY, holder.state.value.sendProblem)
            assertTrue(chat.sent.isEmpty())
            holder.retryHistory()
            advanceUntilIdle()
            assertNull(holder.state.value.problem)
            assertEquals(1, holder.state.value.messages.size)
            assertTrue(chat.synced.isNotEmpty())
        }

    @Test
    fun openingPostsAnUnsettledMessageAgainOnceAndNeverWhileTheHistoryIsBroken() =
        runTest {
            val info = group(1, "bob")
            store.insert(gif(1, 1, null, at = 5, state = DeliveryState.SENDING))
            val history = FlakyHistory()
            val holder = ConversationStateHolder(this, sessions(), chat, history, marks, info, CaptionInput(CodePointCounter))
            holder.open()
            advanceUntilIdle()
            assertEquals(1, chat.resumed)
            holder.refresh()
            advanceUntilIdle()
            assertEquals(1, chat.resumed, "only when the conversation is opened")
            val broken = FlakyHistory().also { it.broken = HistoryStoreException.Kind.CIPHER }
            val other = ConversationStateHolder(this, sessions(), chat, broken, marks, info, CaptionInput(CodePointCounter))
            other.open()
            advanceUntilIdle()
            assertEquals(1, chat.resumed, "a broken history is not resumed into")
        }

    @Test
    fun unreadableRecordsAreCountedForTheScreen() =
        runTest {
            val info = group(1, "bob")
            store.insert(gif(1, 1, "bob", at = 5))
            val history = FlakyHistory().also { it.unreadableRecords = 3 }
            val holder = ConversationStateHolder(this, sessions(), chat, history, marks, info, CaptionInput(CodePointCounter))
            holder.open()
            advanceUntilIdle()
            assertEquals(3, holder.state.value.unreadable)
            assertEquals(1, holder.state.value.messages.size)
        }

    @Test
    fun aListOverABrokenHistoryDoesNotFetchAndTheHistoryBannerWinsOverASyncProblem() =
        runTest {
            chat.groups = listOf(group(1, "bob"), group(2, "cy"))
            val history = FlakyHistory().also { it.broken = HistoryStoreException.Kind.KEY }
            val holder = ChatListStateHolder(this, sessions(), chat, history, marks)
            repeat(3) {
                holder.refresh()
                advanceUntilIdle()
            }
            assertTrue(chat.synced.isEmpty(), "no client restore and fetch per group and tick")
            assertEquals(0, chat.welcomed)
            assertEquals(2, holder.state.value.conversations.size)
            assertEquals(ChatProblem.HISTORY, holder.state.value.problem)
            chat.failWith = AuthError.Api(ApiError.NETWORK)
            holder.refresh()
            advanceUntilIdle()
            assertEquals(2, holder.state.value.conversations.size, "what is known stays")
        }

    @Test
    fun aHistoryThatClosesBetweenTheReadsIsTheNamedStateNotAnException() =
        runTest {
            val info = group(1, "bob")
            store.insert(gif(1, 1, "bob", at = 5))
            val closing =
                object : HistoryAccess by store {
                    override suspend fun unreadable(groupId: KeyBytes): Int = throw HistoryStoreException(HistoryStoreException.Kind.CLOSED)
                }
            val holder = ConversationStateHolder(this, sessions(), chat, closing, marks, info, CaptionInput(CodePointCounter))
            holder.open()
            advanceUntilIdle()
            assertEquals(ChatProblem.HISTORY, holder.state.value.problem)
        }

    @Test
    fun aRetryDuringARunningRefreshIsNotDropped() =
        runTest {
            val info = group(1, "bob")
            store.insert(gif(1, 1, "bob", at = 5))
            val history = FlakyHistory().also { it.broken = HistoryStoreException.Kind.STORAGE }
            val gate = CompletableDeferred<Unit>()
            chat.onSync = { gate.await() }
            val holder = ConversationStateHolder(this, sessions(), chat, history, marks, info, CaptionInput(CodePointCounter))
            holder.open()
            advanceUntilIdle()
            assertEquals(ChatProblem.HISTORY, holder.state.value.problem)
            history.broken = null
            holder.refresh()
            advanceUntilIdle()
            holder.retryHistory()
            advanceUntilIdle()
            gate.complete(Unit)
            advanceUntilIdle()
            assertNull(holder.state.value.problem)
            assertEquals(1, history.retries)
        }

    @Test
    fun leavingClearsAPendingRetryRefreshSoNoSecondRefreshStartsAfterwards() =
        runTest {
            val info = group(1, "bob")
            store.insert(gif(1, 1, "bob", at = 5))
            val history = FlakyHistory()
            val gate = CompletableDeferred<Unit>()
            chat.onSync = { gate.await() }
            val holder = ConversationStateHolder(this, sessions(), chat, history, marks, info, CaptionInput(CodePointCounter))
            holder.open()
            advanceUntilIdle()
            holder.retryHistory()
            advanceUntilIdle()
            val before = chat.synced.size
            holder.leave()
            gate.complete(Unit)
            advanceUntilIdle()
            assertEquals(before, chat.synced.size, "no refresh follows a visit that was left")
        }

    @Test
    fun resetIsOfferedOnlyForABrokenKeyAndClearsTheProblemWhenItWorks() =
        runTest {
            chat.groups = listOf(group(1, "bob"))
            store.insert(gif(1, 1, "bob", at = 5))
            val history =
                FlakyHistory().also {
                    it.broken = HistoryStoreException.Kind.KEY
                    it.resettable = true
                }
            val holder = ChatListStateHolder(this, sessions(), chat, history, marks)
            holder.refresh()
            advanceUntilIdle()
            assertTrue(holder.state.value.canResetHistory)
            holder.resetHistory()
            advanceUntilIdle()
            assertEquals(1, history.resets)
            assertFalse(holder.state.value.canResetHistory)
            assertNull(holder.state.value.problem)
            // A failing storage is not a reason to offer a destructive action.
            history.broken = HistoryStoreException.Kind.STORAGE
            holder.refresh()
            advanceUntilIdle()
            assertFalse(holder.state.value.canResetHistory)
        }

    @Test
    fun aResetThatFailsIsNamedAndTheConversationStaysAsItWas() =
        runTest {
            val info = group(1, "bob")
            store.insert(gif(1, 1, "bob", at = 5))
            val history =
                FlakyHistory().also {
                    it.broken = HistoryStoreException.Kind.KEY
                    it.resettable = true
                    it.resetResult = ResetResult.FAILED
                }
            val holder = ConversationStateHolder(this, sessions(), chat, history, marks, info, CaptionInput(CodePointCounter))
            holder.open()
            advanceUntilIdle()
            assertTrue(holder.state.value.canResetHistory)
            holder.resetHistory()
            advanceUntilIdle()
            assertTrue(holder.state.value.resetFailed)
            assertEquals(ChatProblem.HISTORY, holder.state.value.problem, "still a broken history: no log read, no send")
            assertTrue(
                holder.state.value.messages
                    .isEmpty(),
            )
            history.resetResult = ResetResult.DONE
            holder.resetHistory()
            advanceUntilIdle()
            assertFalse(holder.state.value.resetFailed)
            assertNull(holder.state.value.problem)
            assertEquals(1, holder.state.value.messages.size)
        }

    @Test
    fun theResetOfferDoesNotSurviveAHealthyHistoryEvenWhenTheNextRefreshFails() =
        runTest {
            chat.groups = listOf(group(1, "bob"))
            val history =
                FlakyHistory().also {
                    it.broken = HistoryStoreException.Kind.KEY
                    it.resettable = true
                }
            val holder = ChatListStateHolder(this, sessions(), chat, history, marks)
            holder.refresh()
            advanceUntilIdle()
            assertTrue(holder.state.value.canResetHistory)
            holder.resetHistory()
            advanceUntilIdle()
            chat.failWith = AuthError.Api(ApiError.NETWORK)
            holder.refresh()
            advanceUntilIdle()
            assertFalse(holder.state.value.canResetHistory, "the history is healthy again; the offer is gone though the groups call failed")
        }

    @Test
    fun aFrozenChatSaysWhyInWordsThatNameNobody() {
        assertEquals(ConversationProblem.MEMBERS_MISMATCH, GroupStatus.FROZEN_ENVELOPE_MISMATCH.toProblem())
        assertEquals(ConversationProblem.CONTACT_KEY_MISMATCH, GroupStatus.FROZEN_CONTACT_MISMATCH.toProblem())
        assertEquals(ConversationProblem.STOPPED, GroupStatus.FROZEN_GAP.toProblem())
        assertEquals(ConversationProblem.STOPPED, GroupStatus.FROZEN_COMMIT_REFUSED.toProblem())
    }

    private val heart = "\u2764\uFE0F"

    @Test
    fun theConversationKnowsTheOwnNameAndShowsTheReactionsOfAGif() =
        runTest {
            val info = group(1, "bob")
            chat.groups = listOf(info)
            store.insert(gif(1, 1, "bob", at = 5))
            store.react(id(1), id(101), "bob", heart)
            val holder = conversation(info)
            holder.open()
            advanceUntilIdle()
            val state = holder.state.value
            assertEquals("me_user", state.own)
            assertEquals(
                listOf("bob" to heart),
                state.messages
                    .single()
                    .reactions
                    .map { it.by to it.emoji },
            )
        }

    @Test
    fun aReactionGoesThroughTheSendGateAndTheViewShowsItAfterwards() =
        runTest {
            val info = group(1, "bob")
            chat.groups = listOf(info)
            store.insert(gif(1, 1, "bob", at = 5))
            val holder = conversation(info)
            holder.open()
            advanceUntilIdle()
            chat.onSync = { }
            holder.react(id(101), heart)
            store.react(id(1), id(101), "me_user", heart) // what the use case does once the server accepted it
            advanceUntilIdle()
            assertEquals(listOf<Pair<KeyBytes, String?>>(id(101) to heart), chat.reacted)
            assertNull(holder.state.value.sendProblem)
            assertEquals(
                listOf("me_user"),
                holder.state.value.messages
                    .single()
                    .reactions
                    .map { it.by },
            )
            holder.react(id(101), null)
            advanceUntilIdle()
            assertEquals(listOf<Pair<KeyBytes, String?>>(id(101) to heart, id(101) to null), chat.reacted)
        }

    @Test
    fun choosingTheReactionAlreadyThereSendsNothing() =
        runTest {
            val info = group(1, "bob")
            chat.groups = listOf(info)
            store.insert(gif(1, 1, "bob", at = 5))
            store.react(id(1), id(101), "me_user", heart)
            val holder = conversation(info)
            holder.open()
            advanceUntilIdle()
            holder.react(id(101), heart)
            holder.react(id(999), heart) // a message that is not in the conversation
            advanceUntilIdle()
            assertEquals(emptyList(), chat.reacted)
            assertNull(holder.state.value.sendProblem)
        }

    @Test
    fun aReactionTheUseCaseRefusesSaysSoInsteadOfPretendingItWorked() =
        runTest {
            val info = group(1, "bob")
            chat.groups = listOf(info)
            store.insert(gif(1, 1, "bob", at = 5))
            val holder = conversation(info)
            holder.open()
            advanceUntilIdle()
            chat.reactOutcome = SyncOutcome.InvalidRequest
            holder.react(id(101), heart)
            advanceUntilIdle()
            assertEquals(SendProblem.REACTION, holder.state.value.sendProblem)
        }

    @Test
    fun theOwnNameIsAskedAgainWhenTheFirstAnswerFailed() =
        runTest {
            val info = group(1, "bob")
            chat.groups = listOf(info)
            store.insert(gif(1, 1, "bob", at = 5))
            chat.failWith = AuthError.Api(ApiError.NETWORK)
            val holder = conversation(info)
            holder.open()
            advanceUntilIdle()
            assertNull(holder.state.value.own)
            chat.failWith = null
            holder.react(id(101), heart)
            advanceUntilIdle()
            assertEquals("me_user", holder.state.value.own)
            assertEquals(listOf<Pair<KeyBytes, String?>>(id(101) to heart), chat.reacted)
        }

    @Test
    fun aReactionThatTheServerSlowsDownSaysSo() =
        runTest {
            val info = group(1, "bob")
            chat.groups = listOf(info)
            store.insert(gif(1, 1, "bob", at = 5))
            val holder = conversation(info)
            holder.open()
            advanceUntilIdle()
            chat.reactOutcome = SyncOutcome.InFlight(ApiError.RATE_LIMITED)
            holder.react(id(101), heart)
            advanceUntilIdle()
            assertEquals(SendProblem.THROTTLED, holder.state.value.sendProblem)
        }

    @Test
    fun onlyAGifThatWasSentOrReceivedTakesReactions() =
        runTest {
            val info = group(1, "bob")
            chat.groups = listOf(info)
            store.insert(gif(1, 1, "bob", at = 5))
            store.insert(gif(1, 2, null, at = 6, state = DeliveryState.SENT))
            store.insert(gif(1, 3, null, at = 7, state = DeliveryState.SENDING))
            store.insert(gif(1, 4, null, at = 8, state = DeliveryState.FAILED))
            store.insert(ChatMessage(id(1), id(105), "bob", null, MessageBody.GroupMeta("Trip"), null, 9L))
            val holder = conversation(info)
            holder.open()
            advanceUntilIdle()
            assertEquals(
                listOf(true, true, false, false, false),
                holder.state.value.messages
                    .map { it.canBeReactedTo },
            )
        }
}
