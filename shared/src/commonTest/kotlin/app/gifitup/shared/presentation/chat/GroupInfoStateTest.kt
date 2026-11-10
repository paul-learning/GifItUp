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
import app.gifitup.shared.domain.groups.GroupInfo
import app.gifitup.shared.domain.groups.GroupList
import app.gifitup.shared.domain.groups.GroupStatus
import app.gifitup.shared.domain.groups.RosterLeaf
import app.gifitup.shared.domain.groups.SyncOutcome
import app.gifitup.shared.domain.groups.WelcomesReport
import app.gifitup.shared.domain.messages.ChatMessage
import app.gifitup.shared.domain.messages.InMemoryMessageStore
import app.gifitup.shared.domain.messages.MessageBody
import app.gifitup.shared.domain.session.SessionHolder
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GroupInfoStateTest {
    private val groupId = KeyBytes(byteArrayOf(1))
    private val store = InMemoryMessageStore()
    private var friendNames: List<String>? = listOf("bob", "carol", "dave", "erin")

    private class FakeChat : ChatPort {
        var adds = listOf<String>()
        var renames = listOf<String>()
        var addOutcome: (String) -> AuthResult<SyncOutcome> = { AuthResult.Ok(SyncOutcome.Sent(2)) }
        var renameOutcome: AuthResult<SyncOutcome> = AuthResult.Ok(SyncOutcome.Sent(3))

        var listed = listOf<GroupInfo>()

        override suspend fun groups(session: Session) = AuthResult.Ok(GroupList("me", listed))

        override suspend fun welcomes(session: Session) = AuthResult.Ok(WelcomesReport(emptyList(), null))

        override suspend fun sync(
            session: Session,
            groupId: KeyBytes,
        ) = AuthResult.Ok<SyncOutcome>(SyncOutcome.CaughtUp(0))

        override suspend fun create(
            session: Session,
            target: String,
        ): AuthResult<GroupCreation> = error("not used")

        override suspend fun add(
            session: Session,
            groupId: KeyBytes,
            target: String,
        ): AuthResult<SyncOutcome> {
            adds += target
            return addOutcome(target)
        }

        override suspend fun addToNewGroup(
            session: Session,
            groupId: KeyBytes,
            target: String,
        ): AuthResult<SyncOutcome> = error("not used")

        override suspend fun rename(
            session: Session,
            groupId: KeyBytes,
            name: String,
        ): AuthResult<SyncOutcome> {
            renames += name
            return renameOutcome
        }

        override suspend fun sendGif(
            session: Session,
            groupId: KeyBytes,
            reference: GifReference,
            caption: String?,
        ) = AuthResult.Ok<SyncOutcome>(SyncOutcome.Sent(1))

        override suspend fun react(
            session: Session,
            groupId: KeyBytes,
            target: KeyBytes,
            emoji: String?,
        ): AuthResult<SyncOutcome> = error("not used")

        var leaveOutcome: AuthResult<SyncOutcome> = AuthResult.Ok(SyncOutcome.CaughtUp(0))
        var leftGroups = listOf<KeyBytes>()

        override suspend fun leave(
            session: Session,
            groupId: KeyBytes,
        ): AuthResult<SyncOutcome> {
            leftGroups = leftGroups + groupId
            return leaveOutcome
        }

        override suspend fun acceptRequest(
            session: Session,
            groupId: KeyBytes,
        ): AuthResult<SyncOutcome> = AuthResult.Ok(SyncOutcome.CaughtUp(0))

        override suspend fun resume(
            session: Session,
            groupId: KeyBytes,
        ) = AuthResult.Ok<SyncOutcome>(SyncOutcome.Sent(1))
    }

    private val chat = FakeChat()
    private val session = Session("d", "t", Long.MAX_VALUE, checkNotNull(ApiEndpoint.forOrigin("https://chat.example.test")))
    private var pins: Map<String, Set<KeyBytes>>? = emptyMap()
    private val names =
        object : FriendNames {
            override suspend fun names(): List<String>? = friendNames

            override suspend fun pinned(): Map<String, Set<KeyBytes>>? = pins

            override suspend fun refresh(session: Session): AuthResult<Unit> = AuthResult.Ok(Unit)
        }

    private fun TestScope.holder(
        vararg others: String,
        leaves: List<RosterLeaf> = emptyList(),
    ) = GroupInfoStateHolder(
        this,
        SessionHolder({ AuthResult.Ok(session) }, this) { 0L },
        chat,
        names,
        store,
        GroupInfo(groupId, GroupStatus.ACTIVE, others.toList(), leaves),
    )

    private suspend fun TestScope.opened(
        vararg others: String,
        leaves: List<RosterLeaf> = emptyList(),
    ): GroupInfoStateHolder =
        holder(*others, leaves = leaves).also {
            it.open()
            advanceUntilIdle()
        }

    private suspend fun named(name: String) {
        store.insert(ChatMessage(groupId, KeyBytes(byteArrayOf(9)), "bob", 1, MessageBody.GroupMeta(name), null, 10))
    }

    @Test
    fun aNamedGroupShowsItsNameItsMembersAndTheFriendsNotInIt() =
        runTest {
            named("Weekend")
            val state = opened("bob").state.value
            assertEquals("Weekend", state.name)
            assertEquals(listOf("bob"), state.members)
            assertEquals(listOf("carol", "dave", "erin"), state.candidates)
            assertTrue(state.canAdd)
        }

    @Test
    fun aDirectChatCannotGrow() =
        runTest {
            val state = opened("bob").state.value
            assertFalse(state.canAdd)
            val holder = opened("bob")
            holder.toggle("carol")
            holder.add()
            advanceUntilIdle()
            assertTrue(chat.adds.isEmpty())
        }

    @Test
    fun chosenFriendsAreAddedOneByOneAndTheNameIsSentAgainAfterwards() =
        runTest {
            named("Weekend")
            val holder = opened("bob", "carol")
            holder.toggle("erin")
            holder.toggle("dave")
            holder.add()
            advanceUntilIdle()
            assertEquals(listOf("dave", "erin"), chat.adds)
            assertEquals(listOf("Weekend"), chat.renames)
            val state = holder.state.value
            assertEquals(listOf("bob", "carol", "dave", "erin"), state.members)
            assertTrue(state.candidates.isEmpty())
            assertNull(state.problem)
        }

    @Test
    fun aFriendWhoCannotBeAddedIsListedAndTheOthersStay() =
        runTest {
            named("Weekend")
            chat.addOutcome = { if (it == "dave") AuthResult.Ok(SyncOutcome.InvalidRequest) else AuthResult.Ok(SyncOutcome.Sent(2)) }
            val holder = opened("bob", "carol")
            holder.toggle("dave")
            holder.toggle("erin")
            holder.add()
            advanceUntilIdle()
            val state = holder.state.value
            assertEquals(GroupInfoProblem.PARTLY, state.problem)
            assertEquals(listOf("dave"), state.notAdded)
            assertEquals(listOf("bob", "carol", "erin"), state.members)
            assertEquals(setOf("dave"), state.selected)
        }

    @Test
    fun aNameThatIsNotAcceptedCanBeSentAgain() =
        runTest {
            named("Weekend")
            chat.renameOutcome = AuthResult.Ok(SyncOutcome.InvalidRequest)
            val holder = opened("bob", "carol")
            holder.toggle("dave")
            holder.add()
            advanceUntilIdle()
            assertEquals(GroupInfoProblem.NAME_NOT_SENT, holder.state.value.problem)
            chat.renameOutcome = AuthResult.Ok(SyncOutcome.Sent(4))
            holder.retryName()
            advanceUntilIdle()
            assertNull(holder.state.value.problem)
            assertEquals(listOf("Weekend", "Weekend"), chat.renames)
        }

    @Test
    fun noNameIsSentForAGroupWithoutOneAndNothingIsSentWhenNobodyWasAdded() =
        runTest {
            val holder = opened("bob", "carol")
            holder.toggle("dave")
            holder.add()
            advanceUntilIdle()
            assertTrue(chat.renames.isEmpty())
            named("Weekend")
            chat.addOutcome = { AuthResult.Failed(AuthError.Api(ApiError.NETWORK)) }
            val second = opened("bob", "carol")
            second.toggle("erin")
            second.add()
            advanceUntilIdle()
            assertEquals(GroupInfoProblem.OFFLINE, second.state.value.problem)
            assertTrue(chat.renames.isEmpty())
        }

    @Test
    fun addingWithNobodyChosenIsAProblemAndNoMoreThanNineAtOnce() =
        runTest {
            named("Weekend")
            val holder = opened("bob", "carol")
            holder.add()
            assertEquals(GroupInfoProblem.NOBODY, holder.state.value.problem)
            friendNames = (1..12).map { "f$it" }
            val many = opened("bob", "carol")
            many.state.value.candidates
                .forEach(many::toggle)
            assertEquals(NewChatStateHolder.MAX_FRIENDS, many.state.value.selected.size)
        }

    @Test
    fun anUnreadableFriendsListIsAProblem() =
        runTest {
            named("Weekend")
            friendNames = null
            assertEquals(GroupInfoProblem.FRIENDS_UNAVAILABLE, opened("bob", "carol").state.value.problem)
        }

    private fun key(n: Int) = KeyBytes(ByteArray(32) { n.toByte() })

    @Test
    fun aMemberIsAFriendOnlyIfTheChatKeyIsTheSavedOneNotByNameAlone() =
        runTest {
            named("Weekend")
            pins = mapOf("bob" to setOf(key(1)), "carol" to setOf(key(2)))
            val leaves = listOf(RosterLeaf("bob", key(1), false), RosterLeaf("carol", key(9), false), RosterLeaf("dave", key(4), false))
            val standing = opened("bob", "carol", "dave", leaves = leaves).state.value.standing
            assertEquals(MemberStanding.FRIEND, standing["bob"])
            assertEquals(MemberStanding.OTHER_KEY, standing["carol"], "carol is a saved friend's name with another key")
            assertEquals(MemberStanding.NOT_SAVED, standing["dave"])
        }

    @Test
    fun anUnreadableContactBookGivesNoStandingAtAll() =
        runTest {
            named("Weekend")
            pins = null
            val state = opened("bob", "carol").state.value
            assertEquals(emptyMap(), state.standing)
            assertTrue(state.standingUnknown, "the screen is told, not left silent")
        }

    @Test
    fun aNameWithoutALeafIsNeverAFriendByItsName() =
        runTest {
            named("Weekend")
            pins = mapOf("bob" to setOf(key(1)))
            // The roster could not be read back: bob is a saved friend's name, but no leaf proves it.
            assertEquals(emptyMap(), opened("bob", "carol").state.value.standing)
        }

    @Test
    fun afterAnAddTheMembersAndTheirStandingAreReadBackFromTheEngine() =
        runTest {
            named("Weekend")
            pins = mapOf("bob" to setOf(key(1)))
            val before = listOf(RosterLeaf("bob", key(1), false), RosterLeaf("carol", key(2), false))
            val holder = opened("bob", "carol", leaves = before)
            chat.listed =
                listOf(
                    GroupInfo(groupId, GroupStatus.ACTIVE, listOf("bob", "carol", "dave"), before + RosterLeaf("dave", key(4), false)),
                )
            holder.toggle("dave")
            holder.add()
            advanceUntilIdle()
            val state = holder.state.value
            assertEquals(listOf("bob", "carol", "dave"), state.members)
            assertEquals(MemberStanding.NOT_SAVED, state.standing["dave"])
            assertEquals(MemberStanding.FRIEND, state.standing["bob"])
        }

    @Test
    fun whenTheReadBackFailsTheAddedNameIsListedButHasNoStanding() =
        runTest {
            named("Weekend")
            pins = mapOf("dave" to setOf(key(4)))
            val holder = opened("bob", "carol", leaves = listOf(RosterLeaf("bob", key(1), false), RosterLeaf("carol", key(2), false)))
            holder.toggle("dave")
            holder.add()
            advanceUntilIdle()
            assertEquals(listOf("bob", "carol", "dave"), holder.state.value.members)
            assertNull(holder.state.value.standing["dave"], "a saved name without a leaf is not a friend by its name")
        }

    @Test
    fun leavingAChatSaysSoOnlyWhenItWorkedAndAFailureKeepsTheChatAndOffersAnotherTry() =
        runTest {
            val holder = opened("bob")
            chat.leaveOutcome = AuthResult.Ok(SyncOutcome.Unavailable(ApiError.NETWORK))
            holder.leaveChat()
            advanceUntilIdle()
            assertFalse(holder.state.value.left)
            assertEquals(GroupInfoProblem.LEAVE_FAILED, holder.state.value.problem)
            chat.leaveOutcome = AuthResult.Ok(SyncOutcome.CaughtUp(0))
            holder.leaveChat()
            advanceUntilIdle()
            assertTrue(holder.state.value.left)
            assertEquals(listOf(groupId, groupId), chat.leftGroups)
            holder.leaveChat()
            advanceUntilIdle()
            assertEquals(2, chat.leftGroups.size, "a chat that was left is not left twice")
        }

    @Test
    fun aLeaveThatCouldNotDeleteTheMessagesIsAFailureNotADeletionAndCanBeTriedAgain() =
        runTest {
            val holder = opened("bob")
            // What ChatService answers when the stored messages cannot be deleted after the keys are gone.
            chat.leaveOutcome = AuthResult.Ok(SyncOutcome.StorageFailed)
            holder.leaveChat()
            advanceUntilIdle()
            assertFalse(holder.state.value.left, "nothing says it was deleted")
            assertEquals(GroupInfoProblem.LEAVE_FAILED, holder.state.value.problem)
            chat.leaveOutcome = AuthResult.Ok(SyncOutcome.CaughtUp(0))
            holder.leaveChat()
            advanceUntilIdle()
            assertTrue(holder.state.value.left, "the screen is not stuck: a second try goes through")
        }
}
