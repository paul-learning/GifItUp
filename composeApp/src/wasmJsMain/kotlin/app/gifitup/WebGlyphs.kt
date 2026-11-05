package app.gifitup

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontFamily
import io.ktor.client.HttpClient
import io.ktor.client.engine.js.Js
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpStatusCode
import androidx.compose.ui.text.platform.Font as bytesFont

/**
 * The web app draws into a canvas and cannot use the browser's or the system's fonts, so it ships its own color emoji
 * font: a subset of Noto Color Emoji with exactly the 256 key-comparison emojis (ADR-0014, `emoji-v1.ttf`, built and
 * checked by `scripts/check-emoji-font.py`). The Friends screen draws the emojis with it once it is registered. A glyph still
 * counts as unknown on the web, so the names carry the comparison.
 */
internal class WebGlyphs : GlyphSupport {
    // Compose state, so a screen that asked before the font arrived redraws once it has.
    override var emojiFont: FontFamily? by mutableStateOf(null)
        private set

    /**
     * Still unknown even with the font registered: registering it doesn't prove that the text engine draws the glyphs
     * (#258 CR-1), and the "compare the names" notice only goes away when a glyph is known to be drawn. This flips to
     * true once the drawing has been seen in a browser.
     */
    override fun supports(emoji: String): Boolean? = null

    /**
     * Fetches the font from the app's own origin (CSP `connect-src 'self'`) and registers it. False on any failure.
     * Only a 200 answer whose bytes start like a TrueType file counts: the web server answers a missing file with the
     * app's `index.html` and status 200, and that must never count as the font (#258 SEC-1).
     */
    suspend fun load(register: suspend (FontFamily) -> Unit): Boolean {
        val bytes =
            runCatching {
                HttpClient(Js).use { client ->
                    val answer: HttpResponse = client.get(FONT_PATH)
                    if (answer.status == HttpStatusCode.OK) answer.readRawBytes() else null
                }
            }.getOrNull()
        val usable = bytes != null && bytes.size in MIN_FONT_BYTES..MAX_FONT_BYTES && isTrueTypeFile(bytes)
        val family = if (usable) FontFamily(bytesFont("GifItUpEmoji", bytes)) else null
        val registered = family != null && runCatching { register(family) }.isSuccess
        emojiFont = if (registered) family else null
        return registered
    }

    private companion object {
        const val FONT_PATH = "emoji-v1.ttf"
        const val MIN_FONT_BYTES = 1_000
        const val MAX_FONT_BYTES = 2_000_000
    }
}

private val TRUETYPE_HEADER = byteArrayOf(0, 1, 0, 0)

/** A TrueType font file starts with the version 0x00010000; an HTML page or an error text never does. */
internal fun isTrueTypeFile(bytes: ByteArray): Boolean =
    bytes.size >= TRUETYPE_HEADER.size && TRUETYPE_HEADER.indices.all { bytes[it] == TRUETYPE_HEADER[it] }
