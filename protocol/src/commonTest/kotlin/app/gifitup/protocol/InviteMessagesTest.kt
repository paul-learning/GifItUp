package app.gifitup.protocol

import kotlinx.serialization.SerializationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Wire format of the invite messages (ADR-0010) under the ADR-0006 rules. */
class InviteMessagesTest {
    @Test
    fun createInviteResponseHasStableFieldNames() {
        val response = CreateInviteResponse("123e4567-e89b-12d3-a456-426614174000", "https://x.example/invite#c", 1_800_000_000L)
        val json = """{"inviteId":"123e4567-e89b-12d3-a456-426614174000","link":"https://x.example/invite#c","expiresAt":1800000000}"""

        assertEquals(json, ProtocolJson.encodeToString(response))
        assertEquals(response, ProtocolJson.decodeFromString<CreateInviteResponse>(json))
    }

    @Test
    fun inviteListRoundTripsWithAnExplicitNullUsedBy() {
        val list =
            InviteListResponse(
                listOf(
                    InviteInfo("a", 1L, 2L, InviteStatus.USED, "bob"),
                    InviteInfo("b", 3L, 4L, InviteStatus.OPEN, null),
                ),
            )
        val json =
            """{"invites":[{"inviteId":"a","createdAt":1,"expiresAt":2,"status":"used","usedBy":"bob"},""" +
                """{"inviteId":"b","createdAt":3,"expiresAt":4,"status":"open","usedBy":null}]}"""

        assertEquals(json, ProtocolJson.encodeToString(list))
        assertEquals(list, ProtocolJson.decodeFromString<InviteListResponse>(json))
    }

    @Test
    fun everyStatusHasItsWireNameAndANewOneDecodesToUnknown() {
        InviteStatus.entries.filter { it != InviteStatus.UNKNOWN }.forEach { status ->
            val info = InviteInfo("a", 1L, 2L, status, null)
            assertEquals(info, ProtocolJson.decodeFromString<InviteInfo>(ProtocolJson.encodeToString(info)))
        }
        val later = """{"inviteId":"a","createdAt":1,"expiresAt":2,"status":"paused","usedBy":null}"""
        assertEquals(InviteStatus.UNKNOWN, ProtocolJson.decodeFromString<InviteInfo>(later).status)
        assertFailsWith<SerializationException> { ProtocolJson.encodeToString(InviteInfo("a", 1L, 2L, InviteStatus.UNKNOWN, null)) }
    }

    @Test
    fun everyFieldIsRequiredIncludingUsedBy() {
        assertFailsWith<SerializationException> {
            ProtocolJson.decodeFromString<InviteInfo>("""{"inviteId":"a","createdAt":1,"expiresAt":2,"status":"open"}""")
        }
        assertFailsWith<SerializationException> { ProtocolJson.decodeFromString<CreateInviteResponse>("""{"inviteId":"a","link":"l"}""") }
    }

    @Test
    fun inviteLimitHasItsWireName() {
        assertEquals("""{"error":"invite_limit"}""", ProtocolJson.encodeToString(ErrorResponse(ApiErrorCode.INVITE_LIMIT)))
    }
}
