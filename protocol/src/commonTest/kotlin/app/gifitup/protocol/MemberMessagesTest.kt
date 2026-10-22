package app.gifitup.protocol

import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MemberMessagesTest {
    @Test
    fun roundTripsAndKeepsTheWireNames() {
        val response =
            MembersResponse(
                listOf(MemberInfo("a1", "ann", MemberRelation.INVITER, listOf(MemberDevice("d1", "AAEC")))),
                more = true,
            )
        val json =
            """{"members":[{"accountId":"a1","username":"ann","relation":"inviter",""" +
                """"devices":[{"deviceId":"d1","signatureKey":"AAEC"}]}],"more":true}"""
        assertEquals(json, ProtocolJson.encodeToString(response))
        assertEquals(response, ProtocolJson.decodeFromString<MembersResponse>(json))
    }

    @Test
    fun anUnknownRelationDecodesAsUnknownAndIsNeverSent() {
        val later = """{"accountId":"a","username":"ann","relation":"colleague","devices":[]}"""
        assertEquals(MemberRelation.UNKNOWN, ProtocolJson.decodeFromString<MemberInfo>(later).relation)
        assertFailsWith<SerializationException> { ProtocolJson.encodeToString(MemberInfo("a", "ann", MemberRelation.UNKNOWN, emptyList())) }
    }

    @Test
    fun everyFieldIsRequired() {
        assertFailsWith<SerializationException> { ProtocolJson.decodeFromString<MembersResponse>("""{"members":[]}""") }
        assertFailsWith<SerializationException> { ProtocolJson.decodeFromString<MembersResponse>("""{"more":false}""") }
        assertFailsWith<SerializationException> {
            ProtocolJson.decodeFromString<MemberInfo>("""{"accountId":"a","username":"ann","relation":"inviter"}""")
        }
        assertFailsWith<SerializationException> { ProtocolJson.decodeFromString<MemberDevice>("""{"deviceId":"d"}""") }
    }
}
