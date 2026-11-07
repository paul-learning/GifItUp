package app.gifitup

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontFamily
import app.gifitup.shared.data.content.ReactionEmojis
import app.gifitup.shared.data.crypto.FingerprintEmojis
import io.ktor.client.HttpClient
import io.ktor.client.engine.js.Js
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpStatusCode
import androidx.compose.ui.text.platform.Font as bytesFont

/**
 * The web app draws into a canvas and cannot use the browser's or the system's fonts, so it ships its own color emoji
 * font: a subset of Noto Color Emoji with exactly the 1156 reaction emojis (ADR-0006 B10a), which include the 256
 * key-comparison emojis (ADR-0014); `emoji-v2.ttf`, built and checked by `scripts/check-emoji-font.py`. The Friends screen
 * and the reactions draw the emojis with it once it is registered. Until it is registered, and if it can't be loaded, every
 * glyph is unknown: the names carry the key comparison and no reaction emoji is offered.
 */
internal class WebGlyphs : GlyphSupport {
    // Compose state, so a screen that asked before the font arrived redraws once it has.
    override var emojiFont: FontFamily? by mutableStateOf(null)
        private set

    /**
     * Drawn once the bundled font is registered, unknown before and if it can't be loaded. This is a claim about the
     * font, not a check of the drawing: the web can't ask the text engine whether a glyph came out (owner decision
     * 2026-11-05: the drawing was seen in a browser and the notice is dropped, ADR-0014). Every glyph is in the font
     * (`scripts/check-emoji-font.py`), and only the emojis of the two tables count (#259 CR-2).
     */
    override fun supports(emoji: String): Boolean? = if (emojiFont != null && emoji in tableEmojis) true else null

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
        /**
         * What the font draws: the reaction table, and the 256 fingerprint emojis (read through their public accessor, the table
         * itself is internal), which are inside it and are listed too so that this holds if either table is ever extended.
         */
        val tableEmojis: Set<String> =
            ReactionEmojis.all.toSet() +
                (0 until FingerprintEmojis.TABLE_SIZE).chunked(FingerprintEmojis.LENGTH).flatMap { chunk ->
                    FingerprintEmojis.cells(ByteArray(FingerprintEmojis.LENGTH) { chunk.getOrElse(it) { 0 }.toByte() }).map { it.emoji }
                }

        const val FONT_PATH = "emoji-v2.ttf"
        const val MIN_FONT_BYTES = 1_000
        const val MAX_FONT_BYTES = 4_000_000
    }
}

private val TRUETYPE_HEADER = byteArrayOf(0, 1, 0, 0)

/** A TrueType font file starts with the version 0x00010000; an HTML page or an error text never does. */
internal fun isTrueTypeFile(bytes: ByteArray): Boolean =
    bytes.size >= TRUETYPE_HEADER.size && TRUETYPE_HEADER.indices.all { bytes[it] == TRUETYPE_HEADER[it] }
