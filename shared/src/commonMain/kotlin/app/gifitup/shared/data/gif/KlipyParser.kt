package app.gifitup.shared.data.gif

import app.gifitup.shared.data.content.GifReference
import app.gifitup.shared.domain.gif.GifFailure
import app.gifitup.shared.domain.gif.GifResult
import app.gifitup.shared.domain.gif.GifTile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Reads Klipy's search and trending answer (shape per the spike, **not yet confirmed live**): `{"data":{"data":[item…],
 * "has_next":bool}}`, item = `{"slug", "type"?, "file":{"sm"|"md":{"webp"|"gif":{"url","width","height"}}}}`.
 *
 * Whole or nothing for the envelope (anything off is [GifFailure.MALFORMED]); per item, a **placeholder** and never a
 * dropped result: an item that is not an object, has a `type` other than `gif` (an ad, or anything new), or whose slug or
 * media fails [GifReference.of] becomes [GifTile.Placeholder], which holds no text. Order and count are kept. Only the
 * validated references leave this class: no title, alt text or other field is read. Preview and send share one slug.
 * Input is capped by the caller; nesting is limited here before the JSON parser sees it.
 */
internal object KlipyParser {
    private const val MAX_ITEMS = 50
    private const val MAX_DEPTH = 12
    private const val GIF = "gif"

    @Suppress("ReturnCount") // One early exit per rule of the envelope: whole or nothing.
    fun parse(body: ByteArray): GifResult {
        val text = body.decodeToString()
        if (nestingDepth(text) > MAX_DEPTH) return malformed()
        val root = parseObject(text) ?: return malformed()
        val data = (root["data"] as? JsonObject) ?: return malformed()
        val items = (data["data"] as? JsonArray) ?: return malformed()
        if (items.size > MAX_ITEMS) return malformed()
        val hasNext =
            when (val flag = data["has_next"]) {
                null -> false
                is JsonPrimitive -> flag.booleanOrNull ?: return malformed()
                else -> return malformed()
            }
        return GifResult.Page(items.map(::tileOf), hasNext)
    }

    private fun malformed(): GifResult = GifResult.Failed(GifFailure.MALFORMED)

    private fun parseObject(text: String): JsonObject? =
        try {
            Json.parseToJsonElement(text) as? JsonObject
        } catch (_: IllegalArgumentException) {
            null
        }

    @Suppress("ReturnCount") // One early exit per rule of an item: any break is a placeholder.
    private fun tileOf(element: JsonElement): GifTile {
        val item = element as? JsonObject ?: return GifTile.Placeholder
        val type = item["type"]
        if (type != null && !isGifType(type)) return GifTile.Placeholder
        val slug = (item["slug"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: return GifTile.Placeholder
        val files = item["file"] as? JsonObject ?: return GifTile.Placeholder
        val preview = reference(slug, files["sm"]) ?: return GifTile.Placeholder
        val send = reference(slug, files["md"]) ?: return GifTile.Placeholder
        return GifTile.Item(preview, send)
    }

    /** The `webp` rendition of a size, else its `gif`; both must pass the rule or there is no reference. */
    private fun reference(
        slug: String,
        size: JsonElement?,
    ): GifReference? {
        val renditions = size as? JsonObject ?: return null
        return listOf("webp", "gif").firstNotNullOfOrNull { format -> media(slug, renditions[format]) }
    }

    private fun isGifType(type: JsonElement): Boolean = type is JsonPrimitive && type.isString && type.contentOrNull == GIF

    @Suppress("ReturnCount") // One early exit per field.
    private fun media(
        slug: String,
        element: JsonElement?,
    ): GifReference? {
        val media = element as? JsonObject ?: return null
        val url = (media["url"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: return null
        val width = (media["width"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull ?: return null
        val height = (media["height"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull ?: return null
        return GifReference.of(GifReference.PROVIDER, slug, url, width, height)
    }

    /** The deepest `{`/`[` nesting, counted outside strings, so a hostile body can't overflow the parser's stack. */
    internal fun nestingDepth(text: String): Int {
        var depth = 0
        var deepest = 0
        var inString = false
        var escaped = false
        for (c in text) {
            when {
                escaped -> {
                    escaped = false
                }

                inString && c == '\\' -> {
                    escaped = true
                }

                c == '"' -> {
                    inString = !inString
                }

                inString -> {
                    Unit
                }

                c == '{' || c == '[' -> {
                    depth++
                    if (depth > deepest) deepest = depth
                }

                c == '}' || c == ']' -> {
                    depth--
                }
            }
        }
        return deepest
    }
}
