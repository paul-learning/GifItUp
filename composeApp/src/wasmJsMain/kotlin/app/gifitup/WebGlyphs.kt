package app.gifitup

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontFamily
import io.ktor.client.HttpClient
import io.ktor.client.engine.js.Js
import io.ktor.client.request.get
import io.ktor.client.statement.readRawBytes
import androidx.compose.ui.text.platform.Font as bytesFont

/**
 * The web app draws into a canvas and cannot use the browser's or the system's fonts, so it ships its own color emoji
 * font: a subset of Noto Color Emoji with exactly the 256 key-comparison emojis (ADR-0014, `emoji-v1.ttf`, built and
 * checked by `scripts/check-emoji-font.py`). A glyph then counts as drawn only once the font is registered with the
 * text engine; until then, and if the font can't be loaded, every glyph is unknown and the names carry the comparison.
 */
internal class WebGlyphs : GlyphSupport {
    // Compose state, so a screen that asked before the font arrived redraws once it has.
    private var loaded by mutableStateOf(false)

    override fun supports(emoji: String): Boolean? = if (loaded) true else null

    /** Fetches the font from the app's own origin (CSP `connect-src 'self'`) and registers it. False on any failure. */
    suspend fun load(register: suspend (FontFamily) -> Unit): Boolean {
        val bytes = runCatching { HttpClient(Js).use { it.get(FONT_PATH).readRawBytes() } }.getOrNull()
        val usable = bytes != null && bytes.isNotEmpty() && bytes.size <= MAX_FONT_BYTES
        loaded = usable && runCatching { register(FontFamily(bytesFont("GifItUpEmoji", bytes))) }.isSuccess
        return loaded
    }

    private companion object {
        const val FONT_PATH = "emoji-v1.ttf"
        const val MAX_FONT_BYTES = 2_000_000
    }
}
