package app.gifitup.shared.data.content

import io.ktor.http.Url

/**
 * A GIF as a provider returned it (ADR-0003 Mode B′): the only way to get one is [of], which applies the rules of the
 * ADR and the GIF spike, so every instance is safe to show and to fetch from. [url] is the string the provider returned
 * and exactly the string that is fetched; nothing is rebuilt from the slug.
 */
class GifReference private constructor(
    val slug: String,
    val url: String,
    val width: Int,
    val height: Int,
) {
    val provider: String get() = PROVIDER

    override fun equals(other: Any?): Boolean =
        other is GifReference && slug == other.slug && url == other.url && width == other.width && height == other.height

    override fun hashCode(): Int = ((slug.hashCode() * 31 + url.hashCode()) * 31 + width) * 31 + height

    override fun toString(): String = "GifReference(provider=$PROVIDER)"

    companion object {
        const val PROVIDER = "klipy"
        const val MAX_SIDE = 2048

        /** The raw string, case-sensitive, no normalisation first; `Regex.matches` is a full match (no trailing newline). */
        private val URL_PATTERN =
            Regex("^https://static[12]?\\.klipy\\.com/ii/[0-9a-f]{32}/[0-9a-f]{2}/[0-9a-f]{2}/[A-Za-z0-9_-]{1,64}\\.(gif|webp)$")
        private val SLUG_PATTERN = Regex("^[A-Za-z0-9_-]{1,128}$")

        /**
         * The reference, or `null` if any field is wrong: [provider] is exactly `klipy`, [slug] and [url] match their
         * patterns, the parsed [url] re-serialises to exactly the input, and the size hints are 1..[MAX_SIDE]. The hints
         * only lay out the tile; the real size is read from the file (#24, structure scanner).
         */
        fun of(
            provider: String,
            slug: String,
            url: String,
            width: Int,
            height: Int,
        ): GifReference? {
            val sizesOk = width in 1..MAX_SIDE && height in 1..MAX_SIDE
            val fieldsOk = provider == PROVIDER && SLUG_PATTERN.matches(slug)
            return if (sizesOk && fieldsOk && validUrl(url)) {
                GifReference(slug, url, width, height)
            } else {
                null
            }
        }

        /** True if [raw] may be fetched: the pattern first, then the platform parser must give the same string back. */
        fun validUrl(raw: String): Boolean = URL_PATTERN.matches(raw) && reserialises(raw)

        // Ktor and each platform throw their own types for a URL they can't parse; all of them mean "not valid".
        @Suppress("TooGenericExceptionCaught")
        private fun reserialises(raw: String): Boolean =
            try {
                Url(raw).toString() == raw
            } catch (_: Exception) {
                false
            }
    }
}
