package app.gifitup.shared.data.content

import app.gifitup.shared.domain.messages.MessageRefs

/** Reads the fields of the content types from the parsed envelope (ADR-0006 B3); see [ContentCodec.decode]. */
internal object ContentFields {
    private val TEXT_FIELDS = listOf("provider", "slug", "url")
    private val SIZE_FIELDS = listOf("width", "height")

    fun typed(
        type: String,
        fields: Map<String, JsonValue>,
    ): Decoded =
        when (type) {
            "gif" -> gif(fields)
            "group_meta" -> groupMeta(fields)
            else -> Decoded.Unsupported
        }

    /**
     * An `ann_reaction`: a target that is a message reference and an `emoji` that is a table entry or an explicit `null`.
     * Anything else, a missing `emoji` included, is dropped (B10).
     */
    fun reaction(fields: Map<String, JsonValue>): Decoded {
        val target = string(fields, "target")?.let { MessageRefs.parse(it) }
        val raw = fields["emoji"]
        return when {
            target == null -> {
                Decoded.Dropped
            }

            raw == JsonValue.Null -> {
                Decoded.Message(Content.Reaction(target, null))
            }

            raw is JsonValue.Str -> {
                ReactionEmojis.canonical(raw.value)?.let { Decoded.Message(Content.Reaction(target, it)) }
                    ?: Decoded.Dropped
            }

            else -> {
                Decoded.Dropped
            }
        }
    }

    private fun gif(fields: Map<String, JsonValue>): Decoded {
        val reference = reference(fields)
        val caption = caption(fields)
        return if (reference == null || caption == null) {
            Decoded.Invalid
        } else {
            Decoded.Message(Content.Gif(reference, caption.ifEmpty { null }))
        }
    }

    private fun reference(fields: Map<String, JsonValue>): GifReference? {
        val text = TEXT_FIELDS.mapNotNull { string(fields, it) }
        val sizes = SIZE_FIELDS.mapNotNull { (fields[it] as? JsonValue.Num)?.int }
        val complete = text.size == TEXT_FIELDS.size && sizes.size == SIZE_FIELDS.size
        return if (complete) GifReference.of(text[0], text[1], text[2], sizes[0], sizes[1]) else null
    }

    /** The trimmed caption, empty for none, or `null` if the field is not a valid caption. */
    private fun caption(fields: Map<String, JsonValue>): String? =
        when (val raw = fields["caption"]) {
            // Absent, or the explicit `null` that our writers send: no caption. Any other type is not a caption.
            null, JsonValue.Null -> ""

            is JsonValue.Str -> TextRules.clean(raw.value, ContentCodec.CAPTION_MAX_CODE_POINTS)

            else -> null
        }

    private fun groupMeta(fields: Map<String, JsonValue>): Decoded {
        val name = string(fields, "name")?.let { TextRules.clean(it, ContentCodec.GROUP_NAME_MAX_CODE_POINTS) }
        return if (name.isNullOrEmpty()) Decoded.Invalid else Decoded.Message(Content.GroupMeta(name))
    }

    private fun string(
        fields: Map<String, JsonValue>,
        name: String,
    ): String? = (fields[name] as? JsonValue.Str)?.value
}
