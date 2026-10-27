package app.gifitup.shared.data.content

/** A parsed JSON value. Only what the content schema reads is kept: no float values, no array elements. */
internal sealed interface JsonValue {
    class Obj(
        val fields: Map<String, JsonValue>,
    ) : JsonValue

    class Str(
        val value: String,
    ) : JsonValue

    /** [int] is `null` for a number that is not a plain integer in the `Int` range (a fraction, an exponent, too big). */
    class Num(
        val int: Int?,
    ) : JsonValue

    data object Null : JsonValue

    /** `true`, `false` and arrays: valid JSON, but no content field has these types. */
    data object Other : JsonValue
}

/**
 * The one JSON parser for received content (ADR-0006 B10, "one parse"): strict RFC 8259, no comments, no trailing commas,
 * and **duplicate keys anywhere make the input invalid** (kotlinx' `parseToJsonElement` keeps the last value silently, so
 * reading `type` with one parser and the fields with another could give two readings of the same bytes). Nesting is
 * limited to [MAX_DEPTH]. Any failure returns `null`; nothing of the input is kept in an error.
 */
internal object StrictJson {
    private const val MAX_DEPTH = 8
    private const val HEX_RADIX = 16
    private const val UNICODE_ESCAPE_DIGITS = 4

    /** RFC 8259 number; groups 1 and 2 are the fraction and the exponent, so a number without both is a plain integer. */
    private val NUMBER = Regex("-?(?:0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")

    fun parse(text: String): JsonValue? {
        val reader = Reader(text)
        val value = reader.value(0) ?: return null
        reader.skipSpace()
        return if (reader.atEnd()) value else null
    }

    private fun isHexDigit(char: Char): Boolean = char in '0'..'9' || char in 'a'..'f' || char in 'A'..'F'

    // A recursive-descent parser: its early returns are the failure exits, and splitting it up would hide the grammar.
    @Suppress("ReturnCount", "TooManyFunctions")
    private class Reader(
        private val text: String,
    ) {
        private var position = 0

        fun atEnd(): Boolean = position >= text.length

        fun skipSpace() {
            while (position < text.length && text[position] in " \t\n\r") position++
        }

        fun value(depth: Int): JsonValue? {
            skipSpace()
            return when (text.getOrNull(position)) {
                '{' -> if (depth < MAX_DEPTH) obj(depth) else null
                '[' -> if (depth < MAX_DEPTH) array(depth) else null
                '"' -> string()?.let { JsonValue.Str(it) }
                't' -> literal("true", JsonValue.Other)
                'f' -> literal("false", JsonValue.Other)
                'n' -> literal("null", JsonValue.Null)
                else -> number()
            }
        }

        private fun literal(
            word: String,
            value: JsonValue,
        ): JsonValue? {
            if (!text.startsWith(word, position)) return null
            position += word.length
            return value
        }

        private fun obj(depth: Int): JsonValue? {
            position++
            val fields = HashMap<String, JsonValue>()
            skipSpace()
            if (text.getOrNull(position) == '}') {
                position++
                return JsonValue.Obj(fields)
            }
            while (true) {
                skipSpace()
                val key = string() ?: return null
                skipSpace()
                if (text.getOrNull(position) != ':') return null
                position++
                val member = value(depth + 1) ?: return null
                if (fields.put(key, member) != null) return null
                skipSpace()
                when (text.getOrNull(position++)) {
                    ',' -> Unit
                    '}' -> return JsonValue.Obj(fields)
                    else -> return null
                }
            }
        }

        private fun array(depth: Int): JsonValue? {
            position++
            skipSpace()
            if (text.getOrNull(position) == ']') {
                position++
                return JsonValue.Other
            }
            while (true) {
                value(depth + 1) ?: return null
                skipSpace()
                when (text.getOrNull(position++)) {
                    ',' -> Unit
                    ']' -> return JsonValue.Other
                    else -> return null
                }
            }
        }

        private fun string(): String? {
            if (text.getOrNull(position) != '"') return null
            position++
            val out = StringBuilder()
            while (true) {
                val unit = text.getOrNull(position++) ?: return null
                when {
                    unit == '"' -> return out.toString()
                    unit < ' ' -> return null
                    unit == '\\' -> out.append(escape() ?: return null)
                    else -> out.append(unit)
                }
            }
        }

        private fun escape(): Char? =
            when (val code = text.getOrNull(position++)) {
                '"', '\\', '/' -> code
                'b' -> '\b'
                'f' -> '\u000C'
                'n' -> '\n'
                'r' -> '\r'
                't' -> '\t'
                'u' -> unicodeEscape()
                else -> null
            }

        private fun unicodeEscape(): Char? {
            val digits = text.substring(position, minOf(text.length, position + UNICODE_ESCAPE_DIGITS))
            if (digits.length != UNICODE_ESCAPE_DIGITS || !digits.all(::isHexDigit)) return null
            position += UNICODE_ESCAPE_DIGITS
            return digits.toInt(HEX_RADIX).toChar()
        }

        private fun number(): JsonValue? {
            val match = NUMBER.matchAt(text, position) ?: return null
            position += match.value.length
            val plain = match.groups[1] == null && match.groups[2] == null
            return JsonValue.Num(if (plain) match.value.toIntOrNull() else null)
        }
    }
}
