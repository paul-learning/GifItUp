package app.gifitup.android

import android.graphics.Paint
import app.gifitup.GlyphSupport

/** Asks the system's default font whether it has a glyph for an emoji (`Paint.hasGlyph`, API 23). */
internal object AndroidGlyphs : GlyphSupport {
    private val paint = Paint()

    override fun supports(emoji: String): Boolean? = paint.hasGlyph(emoji)
}
