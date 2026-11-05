package app.gifitup

import androidx.compose.ui.text.font.FontFamily

/**
 * Whether this device can draw an emoji. A missing glyph is drawn as the same box for every emoji that lacks one, so
 * several different emojis would look identical (ADR-0014): the Friends screen shows a permanent notice unless every
 * glyph is known to be there, and the names carry the comparison either way.
 */
fun interface GlyphSupport {
    /**
     * True if the device draws [emoji], false if it doesn't, null if it can't tell (the web before its bundled font is
     * registered, or if that failed).
     */
    fun supports(emoji: String): Boolean?

    /** A font the emojis must be drawn with (the web ships its own), or null to use the device's. */
    val emojiFont: FontFamily? get() = null
}
