package app.gifitup.shared.data.network

import app.gifitup.protocol.GifProviderKeyRule
import kotlin.jvm.JvmInline

/**
 * The GIF provider's API key as the client holds it (ADR-0003, #40): only a string that passed [GifProviderKeyRule] exists
 * as one, so a URL builder that takes this type never sees a `/`, `?`, `#` or `%` from a server. In memory only: never
 * written to storage, never logged, and not kept past the session that fetched it.
 */
@JvmInline
value class GifProviderKey private constructor(
    private val value: String,
) {
    /** The key, for the request that uses it and nothing else. */
    fun reveal(): String = value

    override fun toString(): String = "GifProviderKey(redacted)"

    companion object {
        /** The key, or `null` if [text] breaks the rule. */
        fun of(text: String): GifProviderKey? = if (GifProviderKeyRule.isValid(text)) GifProviderKey(text) else null
    }
}
