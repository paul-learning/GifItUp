package app.gifitup.server.config

import app.gifitup.protocol.GifProviderKeyRule
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

/**
 * The GIF provider's API key (ADR-0003, #40). **Not** part of [app.gifitup.server.ServerConfig] and not a data class: no
 * `toString` of anything that holds it can print it. Read once at startup from the secret file named by
 * `GIF_PROVIDER_KEY_FILE`; rotating means replacing the file and restarting.
 */
class GifProviderKey private constructor(
    private val value: String,
) {
    /** The key, for the one response that hands it out. */
    fun reveal(): String = value

    override fun toString(): String = "GifProviderKey(redacted)"

    companion object {
        const val VARIABLE = "GIF_PROVIDER_KEY_FILE"

        /** The key's longest form plus a `\r\n`. A bigger file is refused without reading the rest. */
        private const val MAX_FILE_BYTES = GifProviderKeyRule.MAX_LENGTH + 2

        /**
         * `null` if [VARIABLE] is not set (the endpoint then answers 404). Set but empty, or a file that is missing, too big,
         * not ASCII or outside the key rule, fails startup with a fixed message that names the variable and never a path,
         * a byte or the value.
         */
        fun fromEnv(env: Map<String, String>): GifProviderKey? {
            val path = env[VARIABLE] ?: return null
            require(path.isNotEmpty()) { "$VARIABLE is set but empty" }
            val file =
                try {
                    Path.of(path)
                } catch (_: InvalidPathException) {
                    throw IllegalArgumentException("$VARIABLE does not name a readable file")
                }
            return read(file)
        }

        internal fun read(path: Path): GifProviderKey {
            val bytes =
                try {
                    Files.newInputStream(path).use { it.readNBytes(MAX_FILE_BYTES + 1) }
                } catch (_: IOException) {
                    throw IllegalArgumentException("$VARIABLE does not name a readable file")
                }
            require(bytes.size <= MAX_FILE_BYTES) { "$VARIABLE: the file is too big for a key" }
            val raw = String(bytes, Charsets.ISO_8859_1)
            // Exactly one line end is dropped: "key " or "key\r" are not keys.
            val text = if (raw.endsWith("\r\n")) raw.dropLast(2) else raw.removeSuffix("\n")
            require(GifProviderKeyRule.isValid(text)) { "$VARIABLE: the file does not hold a valid key" }
            return GifProviderKey(text)
        }
    }
}
