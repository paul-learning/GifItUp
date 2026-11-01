package app.gifitup.shared.domain.chat

import app.gifitup.shared.domain.contacts.KeyBytes
import app.gifitup.shared.domain.groups.GroupInfo
import app.gifitup.shared.domain.groups.GroupStatus
import app.gifitup.shared.domain.messages.ChatMessage
import app.gifitup.shared.domain.messages.HistoryAccess
import app.gifitup.shared.domain.messages.HistoryStoreException
import app.gifitup.shared.domain.messages.InMemoryMessageStore
import app.gifitup.shared.domain.messages.MessageBody
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AddPolicyTest {
    private val store = InMemoryMessageStore()
    private val policy = AddPolicy(store)
    private val group = KeyBytes(ByteArray(16) { 1 })

    private fun info(vararg others: String) = GroupInfo(group, GroupStatus.ACTIVE, others.toList())

    private suspend fun name(text: String) {
        store.insert(ChatMessage(group, KeyBytes(ByteArray(32) { 2 }), "cy", null, MessageBody.GroupMeta(text), null, 1L))
    }

    @Test
    fun aDirectChatIsRefused() =
        runTest {
            assertFalse(policy.allows(info("bob")))
        }

    @Test
    fun aGroupOfTwoOrMoreOthersMayGrow() =
        runTest {
            assertTrue(policy.allows(info("bob", "cy")))
        }

    @Test
    fun aNamedGroupOfOneMayGrow() =
        runTest {
            name("Just us")
            assertTrue(policy.allows(info("bob")))
        }

    @Test
    fun aChatWhoseMembersAreNotKnownIsRefusedAlsoWhenItIsNamed() =
        runTest {
            assertFalse(policy.allows(info()))
            name("Gone")
            assertFalse(policy.allows(info()), "a name does not make an unreadable roster growable")
        }

    @Test
    fun aHistoryThatFailsOrIsClosedIsRefused() =
        runTest {
            val failing =
                object : HistoryAccess by InMemoryMessageStore() {
                    override suspend fun messages(groupId: KeyBytes): List<ChatMessage> =
                        throw HistoryStoreException(HistoryStoreException.Kind.CLOSED)
                }
            assertFalse(AddPolicy(failing).allows(info("bob", "cy")), "unknown kind: refused, also for three members")
            assertEquals(false, AddPolicy(failing).allows(info("bob")))
        }
}
