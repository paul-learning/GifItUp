package app.gifitup

/** The browser has no way to ask whether a font draws an emoji, so every glyph counts as unknown (ADR-0014). */
internal object WebGlyphs : GlyphSupport {
    override fun supports(emoji: String): Boolean? = null
}
