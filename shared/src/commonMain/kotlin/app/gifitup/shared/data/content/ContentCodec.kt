package app.gifitup.shared.data.content

import app.gifitup.shared.domain.contacts.KeyBytes
import app.gifitup.shared.domain.messages.MessageRefs
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** What a message can say (ADR-0006 B3). */
sealed interface Content {
    /** A GIF with an optional one-line [caption], already trimmed and checked. */
    class Gif(
        val reference: GifReference,
        val caption: String?,
    ) : Content

    class GroupMeta(
        val name: String,
    ) : Content

    /**
     * A reaction to the message [target] (`ann_reaction`, ADR-0006 B10): [emoji] is an entry of [ReactionEmojis], `null` takes
     * the sender's reaction back. An annotation, not a message: it is never shown on its own.
     */
    class Reaction(
        val target: KeyBytes,
        val emoji: String?,
    ) : Content
}

/** What a received payload turned out to be. None of the failures carries any of the input (ADR-0006 B5). */
sealed interface Decoded {
    class Message(
        val content: Content,
    ) : Decoded

    /** Malformed, too big, or a failed field check: shown as "message unavailable". */
    data object Invalid : Decoded

    /** A newer `v` or an unknown type: shown as "update the app". */
    data object Unsupported : Decoded

    /** An annotation (`ann_…`) that is unknown or fails a check: acknowledged, stored nowhere, never shown (B10). */
    data object Dropped : Decoded
}

/**
 * Reads and writes the plaintext inside MLS application messages (ADR-0006 Part B): UTF-8 JSON, `{"v":1,"type":…}`, at
 * most [MAX_BYTES] so that every message is one padding block (B7). The server never sees this format.
 */
object ContentCodec {
    const val MAX_BYTES = 896
    const val CAPTION_MAX_CODE_POINTS = 120
    const val GROUP_NAME_MAX_CODE_POINTS = 64

    private const val VERSION = 1
    private const val ANNOTATION_PREFIX = "ann_"
    private const val REACTION_TYPE = "ann_reaction"

    /** Total: any bytes give an answer, nothing throws. */
    fun decode(bytes: ByteArray): Decoded {
        val text = if (bytes.size <= MAX_BYTES) strictUtf8(bytes) else null
        val root = (text?.let { StrictJson.parse(it) } as? JsonValue.Obj)?.fields
        val type = (root?.get("type") as? JsonValue.Str)?.value
        val version = (root?.get("v") as? JsonValue.Num)?.int
        return when {
            root == null || type == null -> Decoded.Invalid

            // An annotation that is of an unknown type, has a version other than 1 or fails a check is dropped (B10).
            type.startsWith(ANNOTATION_PREFIX) -> annotation(type, version, root)

            version == null || version < 1 -> Decoded.Invalid

            version > VERSION -> Decoded.Unsupported

            else -> ContentFields.typed(type, root)
        }
    }

    private fun annotation(
        type: String,
        version: Int?,
        root: Map<String, JsonValue>,
    ): Decoded = if (type == REACTION_TYPE && version == VERSION) ContentFields.reaction(root) else Decoded.Dropped

    /**
     * The bytes of a reaction to [target], or `null` to take it back with [emoji] `null`; `null` too if [emoji] is not an entry
     * of [ReactionEmojis]. Always fits [MAX_BYTES].
     */
    fun encodeReaction(
        target: KeyBytes,
        emoji: String?,
    ): ByteArray? {
        val entry = emoji?.let { ReactionEmojis.canonical(it) ?: return null }
        return write(
            buildJsonObject {
                put("v", VERSION)
                put("type", REACTION_TYPE)
                put("target", MessageRefs.format(target))
                put("emoji", entry?.let { JsonPrimitive(it) } ?: JsonNull)
            },
        )
    }

    /**
     * The bytes of a GIF message, or `null` if the caption breaks the rules (B3a, 120 code points after
     * trimming) or the result would be over [MAX_BYTES].
     */
    fun encodeGif(
        reference: GifReference,
        caption: String?,
    ): ByteArray? {
        val clean = caption?.let { TextRules.clean(it, CAPTION_MAX_CODE_POINTS) ?: return null }
        return write(
            buildJsonObject {
                put("v", VERSION)
                put("type", "gif")
                put("provider", reference.provider)
                put("slug", reference.slug)
                put("url", reference.url)
                put("width", reference.width)
                put("height", reference.height)
                put("caption", clean?.takeIf { it.isNotEmpty() }?.let { JsonPrimitive(it) } ?: JsonNull)
            },
        )
    }

    /**
     * The bytes of a rename, or `null` if [name] breaks the rules (B3a, 1..64 code points after
     * trimming) or the result would be over [MAX_BYTES].
     */
    fun encodeGroupMeta(name: String): ByteArray? {
        val clean = TextRules.clean(name, GROUP_NAME_MAX_CODE_POINTS)?.takeIf { it.isNotEmpty() } ?: return null
        return write(
            buildJsonObject {
                put("v", VERSION)
                put("type", "group_meta")
                put("name", clean)
            },
        )
    }

    private fun write(element: JsonElement): ByteArray? =
        ContentJson.encodeToString(JsonElement.serializer(), element).encodeToByteArray().takeIf { it.size <= MAX_BYTES }

    @Suppress("TooGenericExceptionCaught") // Every platform throws its own type for bad UTF-8; all mean "invalid".
    private fun strictUtf8(bytes: ByteArray): String? =
        try {
            bytes.decodeToString(throwOnInvalidSequence = true)
        } catch (_: Exception) {
            null
        }
}
