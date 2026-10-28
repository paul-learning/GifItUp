package app.gifitup.shared.presentation.chat

import app.gifitup.shared.data.content.GifReference
import app.gifitup.shared.data.network.ApiEndpoint
import app.gifitup.shared.data.network.ApiError
import app.gifitup.shared.data.network.Session
import app.gifitup.shared.domain.auth.AuthError
import app.gifitup.shared.domain.auth.AuthResult
import app.gifitup.shared.domain.chat.ChatPort
import app.gifitup.shared.domain.chat.FriendNames
import app.gifitup.shared.domain.contacts.KeyBytes
import app.gifitup.shared.domain.groups.GroupCreation
import app.gifitup.shared.domain.groups.GroupList
import app.gifitup.shared.domain.groups.SyncOutcome
import app.gifitup.shared.domain.groups.WelcomesReport
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

class NewChatStateTest {
    private val groupId = byteArrayOf(7)

    private class FakeChat : ChatPort {
        var created = listOf<String>()
        var added = listOf<Pair<KeyBytes, String>>()
        var beforeCreate: suspend () -> Unit = {}
        var createOutcome: AuthResult<GroupCreation> = AuthResult.Ok(GroupCreation(byteArrayOf(7), SyncOutcome.Sent(1)))
        var addOutcome: (String) -> SyncOutcome = { SyncOutcome.Sent(2) }

        override suspend fun groups(session: Session) = AuthResult.Ok(GroupList("me", emptyList()))

        override suspend fun welcomes(session: Session) = AuthResult.Ok(WelcomesReport(emptyList(), null))

        override suspend fun sync(
            session: Session,
            groupId: KeyBytes,
        ) = AuthResult.Ok<SyncOutcome>(SyncOutcome.CaughtUp(0))

        override suspend fun create(
            session: Session,
            target: String,
        ): AuthResult<GroupCreation> {
            created += target
            beforeCreate()
            return createOutcome
        }

        override suspend fun add(
            session: Session,
            groupId: KeyBytes,
            target: String,
        ): AuthResult<SyncOutcome> {
            added += groupId to target
            return AuthResult.Ok(addOutcome(target))
        }

        override suspend fun sendGif(
            session: Session,
            groupId: KeyBytes,
            reference: GifReference,
            caption: String?,
        ) = AuthResult.Ok<SyncOutcome>(SyncOutcome.Sent(1))

        override suspend fun resume(
            session: Session,
            groupId: KeyBytes,
        ) = AuthResult.Ok<SyncOutcome>(SyncOutcome.Sent(1))
    }

    private val chat = FakeChat()
    private val session = Session("d", "t", Long.MAX_VALUE, checkNotNull(ApiEndpoint.forOrigin("https://chat.example.test")))
    private var friends: List<String>? = listOf("carol", "bob", "dave")

    private var refreshAnswer: AuthResult<Unit> = AuthResult.Ok(Unit)
    private var refreshed = 0

    private val names =
        object : FriendNames {
            override suspend fun names(): List<String>? = friends

            override suspend fun refresh(session: Session): AuthResult<Unit> {
                refreshed++
                return refreshAnswer
            }
        }

    private fun TestScope.holder() = NewChatStateHolder(this, SessionHolder({ AuthResult.Ok(session) }, this) { 0L }, chat, names)

    private suspend fun TestScope.opened(): NewChatStateHolder =
        holder().also {
            it.open()
            advanceUntilIdle()
        }

    @Test
    fun theFriendsAreListedSortedAndAnUnreadableBookIsAProblem() =
        runTest {
            assertEquals(listOf("bob", "carol", "dave"), opened().state.value.friends)
            friends = null
            assertEquals(NewChatProblem.FRIENDS_UNAVAILABLE, opened().state.value.problem)
        }

    @Test
    fun theMemberListIsReadFirstAndAFailureKeepsWhatIsKnown() =
        runTest {
            val holder = opened()
            assertEquals(1, refreshed)
            assertNull(holder.state.value.problem)
            refreshAnswer = AuthResult.Failed(AuthError.Api(ApiError.NETWORK))
            val offline = opened()
            assertEquals(NewChatProblem.OFFLINE, offline.state.value.problem)
            assertEquals(listOf("bob", "carol", "dave"), offline.state.value.friends, "the saved friends are still listed")
        }

    @Test
    fun oneFriendMakesAOneToOneChat() =
        runTest {
            val holder = opened()
            holder.toggle("bob")
            assertTrue(holder.state.value.canCreate)
            holder.create()
            advanceUntilIdle()
            val state = holder.state.value
            assertEquals(listOf("bob"), chat.created)
            assertTrue(chat.added.isEmpty())
            assertEquals(KeyBytes(groupId), state.created)
            assertNull(state.problem)
            assertFalse(state.creating)
            assertFalse(state.canCreate, "the group exists: no second one")
        }

    @Test
    fun severalFriendsMakeAGroupWithOneCommitEach() =
        runTest {
            val holder = opened()
            listOf("dave", "bob", "carol").forEach(holder::toggle)
            holder.create()
            advanceUntilIdle()
            assertEquals(listOf("bob"), chat.created, "the first (sorted) creates")
            assertEquals(listOf("carol", "dave"), chat.added.map { it.second })
            assertTrue(chat.added.all { it.first == KeyBytes(groupId) })
            assertNull(holder.state.value.problem)
        }

    @Test
    fun aFriendThatCantBeAddedDoesNotUndoTheGroup() =
        runTest {
            chat.addOutcome = { if (it == "carol") SyncOutcome.InvalidRequest else SyncOutcome.Sent(2) }
            val holder = opened()
            listOf("bob", "carol", "dave").forEach(holder::toggle)
            holder.create()
            advanceUntilIdle()
            val state = holder.state.value
            assertEquals(NewChatProblem.PARTLY, state.problem)
            assertEquals(listOf("carol"), state.notAdded)
            assertNotNull(state.created)
            assertEquals(listOf("carol", "dave"), chat.added.map { it.second }, "dave was still added")
        }

    @Test
    fun problemsOfTheFirstStepAreNamed() =
        runTest {
            val cases =
                mapOf(
                    AuthResult.Ok(GroupCreation(null, SyncOutcome.InvalidRequest)) to NewChatProblem.CANT_REACH,
                    AuthResult.Ok(GroupCreation(byteArrayOf(7), SyncOutcome.InFlight(ApiError.NETWORK))) to NewChatProblem.UNFINISHED,
                    AuthResult.Failed(AuthError.Api(ApiError.NETWORK)) to NewChatProblem.OFFLINE,
                    AuthResult.Failed(AuthError.Local.NOT_REGISTERED) to NewChatProblem.NOT_SIGNED_IN,
                    AuthResult.Failed(AuthError.Local.CRYPTO) to NewChatProblem.FAILED,
                )
            for ((answer, problem) in cases) {
                chat.createOutcome = answer
                val holder = opened()
                holder.toggle("bob")
                holder.create()
                advanceUntilIdle()
                assertEquals(problem, holder.state.value.problem, answer.toString())
            }
        }

    @Test
    fun anUnconfirmedFirstStepNamesTheFriendsThatAreNotInYet() =
        runTest {
            chat.createOutcome = AuthResult.Ok(GroupCreation(byteArrayOf(7), SyncOutcome.InFlight(ApiError.NETWORK)))
            val holder = opened()
            listOf("bob", "carol", "dave").forEach(holder::toggle)
            holder.create()
            advanceUntilIdle()
            assertEquals(NewChatProblem.UNFINISHED, holder.state.value.problem)
            assertEquals(listOf("carol", "dave"), holder.state.value.notAdded)
            assertTrue(chat.added.isEmpty())
        }

    @Test
    fun leavingAndComingBackWhileACreateRunsNeverMakesASecondGroup() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            chat.beforeCreate = { gate.await() }
            val holder = opened()
            holder.toggle("bob")
            holder.create()
            advanceUntilIdle()
            assertTrue(holder.state.value.creating)
            holder.leave()
            holder.open()
            advanceUntilIdle()
            assertTrue(holder.state.value.creating, "the screen says one is being made")
            holder.create()
            advanceUntilIdle()
            assertEquals(listOf("bob"), chat.created, "one group only")
            gate.complete(Unit)
            advanceUntilIdle()
            val done = holder.state.value
            assertFalse(done.creating, "no stuck spinner")
            assertNotNull(done.created, "the result of the running create is shown")
            assertNull(done.problem)
            holder.leave()
            holder.open()
            advanceUntilIdle()
            holder.toggle("carol")
            assertTrue(holder.state.value.canCreate, "the next visit starts clean")
        }

    @Test
    fun aCreateThatFinishesWhileTheScreenIsAwayIsNotLostNorRepeated() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            chat.beforeCreate = { gate.await() }
            val holder = opened()
            holder.toggle("bob")
            holder.create()
            advanceUntilIdle()
            holder.leave()
            gate.complete(Unit)
            advanceUntilIdle()
            holder.open()
            advanceUntilIdle()
            val state = holder.state.value
            assertNotNull(state.created, "the finished group is still shown")
            assertFalse(state.canCreate, "so the same friends can't make a second one")
            assertEquals(listOf("bob"), chat.created)
        }

    @Test
    fun anUnreadableFriendsListDuringACreateKeepsTheSpinner() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            chat.beforeCreate = { gate.await() }
            val holder = opened()
            holder.toggle("bob")
            holder.create()
            advanceUntilIdle()
            friends = null
            holder.open()
            advanceUntilIdle()
            assertTrue(holder.state.value.creating)
            gate.complete(Unit)
            advanceUntilIdle()
            assertNotNull(holder.state.value.created)
        }

    @Test
    fun selectionRulesAndNobodyChosen() =
        runTest {
            friends = (1..12).map { "friend$it" }
            val holder = opened()
            holder.create()
            assertEquals(NewChatProblem.NOBODY, holder.state.value.problem)
            holder.toggle("stranger")
            assertTrue(
                holder.state.value.selected
                    .isEmpty(),
                "only listed friends can be chosen",
            )
            (1..12).forEach { holder.toggle("friend$it") }
            assertEquals(NewChatStateHolder.MAX_FRIENDS, holder.state.value.selected.size)
            holder.toggle("friend1")
            assertEquals(NewChatStateHolder.MAX_FRIENDS - 1, holder.state.value.selected.size)
            holder.leave()
            assertEquals(NewChatState(), holder.state.value)
        }

    @Test
    fun howLongAgoIsCountedInSteps() {
        assertIs<Ago.JustNow>(Ago.of(1_000, 1_000))
        assertIs<Ago.JustNow>(Ago.of(1_000, 2_000))
        assertIs<Ago.JustNow>(Ago.of(1_059, 1_000))
        assertEquals(1, assertIs<Ago.Minutes>(Ago.of(1_060, 1_000)).count)
        assertEquals(59, assertIs<Ago.Minutes>(Ago.of(1_000 + 3_599, 1_000)).count)
        assertEquals(1, assertIs<Ago.Hours>(Ago.of(1_000 + 3_600, 1_000)).count)
        assertEquals(23, assertIs<Ago.Hours>(Ago.of(1_000 + 86_399, 1_000)).count)
        assertEquals(1, assertIs<Ago.Days>(Ago.of(1_000 + 86_400, 1_000)).count)
        assertEquals(999, assertIs<Ago.Days>(Ago.of(1_000 + 86_400L * 5_000, 1_000)).count)
    }
}
