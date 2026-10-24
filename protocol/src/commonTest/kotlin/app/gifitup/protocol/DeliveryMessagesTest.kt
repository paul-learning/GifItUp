package app.gifitup.protocol

import kotlinx.serialization.SerializationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Wire format of the Delivery Service messages (ADR-0015) under the ADR-0006 rules. */
class DeliveryMessagesTest {
    private val application =
        PostMessageRequest(
            clientMsgId = "AAAAAAAAAAAAAAAAAAAAAA",
            kind = DeliveryKinds.APPLICATION,
            epoch = null,
            added = emptyList(),
            removed = emptyList(),
            welcomes = emptyList(),
            payload = "AQID",
        )

    @Test
    fun postRequestHasStableFieldNamesAndKeepsNullEpoch() {
        val json =
            """{"clientMsgId":"AAAAAAAAAAAAAAAAAAAAAA","kind":"application","epoch":null,"added":[],"removed":[],""" +
                """"welcomes":[],"payload":"AQID"}"""
        assertEquals(json, ProtocolJson.encodeToString(application))
        assertEquals(application, ProtocolJson.decodeFromString<PostMessageRequest>(json))
    }

    @Test
    fun aCommitCarriesEpochAddedAndWelcomes() {
        val commit =
            application.copy(
                kind = DeliveryKinds.COMMIT,
                epoch = 0,
                added = listOf("d1"),
                welcomes = listOf(WelcomeItem("d1", "BAUG")),
            )
        val request = CreateGroupRequest(groupId = "Z3JvdXA", commit = commit)
        val json = ProtocolJson.encodeToString(request)
        assertEquals(request, ProtocolJson.decodeFromString<CreateGroupRequest>(json))
    }

    @Test
    fun aMissingFieldFailsToDecode() {
        assertFailsWith<SerializationException> {
            ProtocolJson.decodeFromString<PostMessageRequest>("""{"clientMsgId":"a","kind":"application"}""")
        }
    }

    @Test
    fun responsesIgnoreUnknownFieldsFromANewerServer() {
        val fetched =
            ProtocolJson.decodeFromString<FetchMessagesResponse>(
                """{"oldestSeq":1,"lastSeq":2,"messages":[{"seq":2,"senderDeviceId":null,"kind":"application",""" +
                    """"epoch":null,"added":[],"removed":[],"payload":"AQID","later":1}],"later":true}""",
            )
        assertEquals(1L, fetched.oldestSeq)
        assertEquals(null, fetched.messages.single().senderDeviceId)
        val status = GroupStatusResponse(epoch = 1, oldestSeq = 1, lastSeq = 2, ackedSeq = 0, lastSentSeq = null, lastClientMsgId = null)
        assertEquals(status, ProtocolJson.decodeFromString<GroupStatusResponse>(ProtocolJson.encodeToString(status)))
    }

    @Test
    fun socketFramesAndCloseCodes() {
        assertEquals("""{"v":1,"token":"t"}""", ProtocolJson.encodeToString(SocketHello(1, "t")))
        assertEquals("""{"groupId":"g","seq":3,"kind":"application"}""", ProtocolJson.encodeToString(SocketPush("g", 3, "application")))
        assertEquals(4001, SocketCloseCodes.CLIENT_OUTDATED)
        assertEquals(4002, SocketCloseCodes.SERVER_OUTDATED)
    }

    @Test
    fun theNewErrorCodesHaveTheirWireNames() {
        for ((code, wire) in listOf(
            ApiErrorCode.ENVELOPE_INVALID to "envelope_invalid",
            ApiErrorCode.ENVELOPE_CONFLICT to "envelope_conflict",
            ApiErrorCode.EPOCH_CONFLICT to "epoch_conflict",
            ApiErrorCode.GROUP_FULL to "group_full",
            ApiErrorCode.WELCOME_LIMIT to "welcome_limit",
            ApiErrorCode.GROUP_LIMIT to "group_limit",
        )) {
            assertEquals("""{"error":"$wire"}""", ProtocolJson.encodeToString(ErrorResponse(code)))
            assertEquals(code, ProtocolJson.decodeFromString<ErrorResponse>("""{"error":"$wire"}""").error)
        }
    }
}
