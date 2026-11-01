package app.gifitup.shared.domain.chat

import app.gifitup.shared.domain.contacts.KeyBytes
import app.gifitup.shared.domain.messages.ChatMessage
import app.gifitup.shared.domain.messages.DeliveryState
import app.gifitup.shared.domain.messages.MessageBody
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ChatKindTest {
    @Test
    fun theKindFollowsTheNameTheMembersAndWhatCannotBeRead() {
        assertEquals(ChatKind.DIRECT, chatKind(others = 1, named = false, unreadable = 0))
        assertEquals(ChatKind.GROUP, chatKind(others = 1, named = true, unreadable = 0), "a named group of one")
        assertEquals(ChatKind.GROUP, chatKind(others = 2, named = false, unreadable = 0))
        assertEquals(ChatKind.GROUP, chatKind(others = 0, named = true, unreadable = 0), "everyone else left: still a group")
        assertEquals(ChatKind.UNKNOWN, chatKind(others = 1, named = false, unreadable = 1), "the name may be in the unreadable record")
        assertEquals(ChatKind.UNKNOWN, chatKind(others = 0, named = false, unreadable = 0), "members not known")
        assertEquals(ChatKind.GROUP, chatKind(others = 1, named = true, unreadable = 3), "a known name wins")
    }

    private fun meta(
        n: Int,
        name: String,
        state: DeliveryState?,
    ) = ChatMessage(KeyBytes(ByteArray(16)), KeyBytes(ByteArray(32) { n.toByte() }), null, null, MessageBody.GroupMeta(name), state, 1L)

    @Test
    fun onlyASettledNameCounts() {
        assertNull(settledName(emptyList()))
        assertNull(settledName(listOf(meta(1, "x", DeliveryState.SENDING))))
        assertNull(settledName(listOf(meta(1, "x", DeliveryState.FAILED))))
        assertEquals("x", settledName(listOf(meta(1, "x", DeliveryState.SENT))))
        assertEquals("y", settledName(listOf(meta(1, "x", null), meta(2, "y", null))), "the newest wins")
        assertEquals("x", settledName(listOf(meta(1, "x", null), meta(2, "y", DeliveryState.SENDING))), "a sending rename waits")
    }
}
