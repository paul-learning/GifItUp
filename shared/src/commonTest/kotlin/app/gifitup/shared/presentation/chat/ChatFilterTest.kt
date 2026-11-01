package app.gifitup.shared.presentation.chat

import app.gifitup.shared.domain.contacts.KeyBytes
import app.gifitup.shared.domain.groups.GroupInfo
import app.gifitup.shared.domain.groups.GroupStatus
import kotlin.test.Test
import kotlin.test.assertEquals

class ChatFilterTest {
    private fun row(
        n: Int,
        title: ConversationTitle,
    ) = ConversationSummary(
        GroupInfo(KeyBytes(ByteArray(16) { n.toByte() }), GroupStatus.ACTIVE, emptyList()),
        title,
        Preview.Nothing,
        null,
        0,
        null,
    )

    private val direct = row(1, ConversationTitle.Members(listOf("ben")))
    private val group = row(2, ConversationTitle.Members(listOf("ben", "cat")))
    private val named = row(3, ConversationTitle.Named("Weekend"))
    private val all = listOf(direct, group, named)

    @Test
    fun theFiltersSplitDirectChatsFromGroupsAndAllKeepsEverythingInOrder() {
        assertEquals(listOf(direct, group, named), ChatListState(conversations = all).visible)
        assertEquals(listOf(direct), ChatListState(conversations = all, filter = ChatFilter.DIRECT).visible)
        assertEquals(listOf(group, named), ChatListState(conversations = all, filter = ChatFilter.GROUPS).visible)
    }

    @Test
    fun aNamedChatIsAGroupEvenWithOneOtherMember() {
        assertEquals(listOf(named), ChatListState(conversations = listOf(named), filter = ChatFilter.GROUPS).visible)
        assertEquals(emptyList(), ChatListState(conversations = listOf(named), filter = ChatFilter.DIRECT).visible)
    }
}
