package app.gifitup

/**
 * Whether this device can draw an emoji. A missing glyph is drawn as the same box for every emoji that lacks one, so
 * several different emojis would look identical (ADR-0014): the Friends screen shows a permanent notice unless every
 * glyph is known to be there, and the names carry the comparison either way.
 */
fun interface GlyphSupport {
    /** True if the device draws [emoji], false if it doesn't, null if it can't tell (the web). */
    fun supports(emoji: String): Boolean?
}
