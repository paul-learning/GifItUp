package app.gifitup.shared.data.socket

import app.gifitup.protocol.DeliveryKinds
import app.gifitup.protocol.PROTOCOL_VERSION
import app.gifitup.protocol.ProtocolJson
import app.gifitup.protocol.SocketHello
import app.gifitup.shared.data.network.Base64Url
import app.gifitup.shared.data.network.Session
import app.gifitup.shared.domain.contacts.KeyBytes
import app.gifitup.shared.domain.events.ServerMessage
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

/**
 * The frames of the server's socket (ADR-0015, `protocol/`): the first frame this device sends and the hints it gets. Reading
 * is strict and total: anything that is not exactly one of the three known shapes is `null` and is dropped, never an error
 * and never partly used.
 */
internal object SocketFrames {
    /** More than this is refused before it is parsed: a real hint is under 120 characters. */
    const val MAX_FRAME_CHARS = 1024
    private const val MAX_GROUP_ID_BYTES = 128

    /**
     * The hello (`{"v":…,"token":…}`) for [session]. The token is in this string and in no other output of the app: callers
     * pass it to the connector and never log, print or store it.
     */
    fun hello(session: Session): String =
        ProtocolJson.encodeToString(SocketHello.serializer(), SocketHello(PROTOCOL_VERSION, session.token))

    @Suppress("ReturnCount", "TooGenericExceptionCaught") // One early exit per rule; any parse failure just means "not a hint".
    fun parse(text: String): ServerMessage? {
        if (text.length > MAX_FRAME_CHARS) return null
        val fields =
            try {
                ProtocolJson.parseToJsonElement(text) as? JsonObject
            } catch (_: Exception) {
                null
            } ?: return null
        return when (fields.keys) {
            RESYNC_KEYS -> {
                if ((fields["resync"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ==
                    true
                ) {
                    ServerMessage.Resync
                } else {
                    null
                }
            }

            PUSH_KEYS -> {
                push(fields)
            }

            else -> {
                null
            }
        }
    }

    @Suppress("ReturnCount")
    private fun push(fields: JsonObject): ServerMessage? {
        val kind = text(fields["kind"]) ?: return null
        val seq = (fields["seq"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull ?: return null
        if (seq < 0) return null
        return when (kind) {
            // A welcome hint has no group of its own that this device would act on: it only means "look at the welcomes".
            DeliveryKinds.WELCOME -> {
                if (seq == 0L) ServerMessage.WelcomeHint else null
            }

            DeliveryKinds.COMMIT, DeliveryKinds.APPLICATION -> {
                val group = text(fields["groupId"])?.let { Base64Url.decode(it, 1..MAX_GROUP_ID_BYTES) } ?: return null
                ServerMessage.GroupHint(KeyBytes(group))
            }

            else -> {
                null
            }
        }
    }

    private fun text(element: kotlinx.serialization.json.JsonElement?): String? =
        (element as? JsonPrimitive)?.takeIf { it.isString }?.content

    private val PUSH_KEYS = setOf("groupId", "seq", "kind")
    private val RESYNC_KEYS = setOf("resync")
}
