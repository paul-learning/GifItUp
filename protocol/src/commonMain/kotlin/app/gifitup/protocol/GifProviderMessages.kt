package app.gifitup.protocol

import kotlinx.serialization.Serializable

// The GIF provider's API key (ADR-0003, #40). `GET /config/gif-provider` needs a session and takes no parameters. The
// answer is the same for every caller: nothing about the caller shapes it. The provider is not on the wire: clients
// hard-code it and ignore any provider a server names (ADR-0003, SEC-7). An unconfigured server answers 404
// `not_found`, which is also what a server from before this endpoint answers: "no GIF search yet".

/**
 * `GET /config/gif-provider` → `200`, `Cache-Control: no-store`. [apiKey] matches [GifProviderKeyRule]; a client
 * refuses the whole answer otherwise. Every field is required (ADR-0006 A7).
 */
@Serializable
data class GifProviderResponse(
    val apiKey: String,
)

/**
 * The one rule for the key, on the server (startup) and in the client (every answer): ADR-0003's starting rule, which
 * #23 may tighten once the real key's shape is known. Only these characters ever reach a provider URL.
 */
object GifProviderKeyRule {
    private val PATTERN = Regex("^[A-Za-z0-9_-]{16,128}$")

    const val MAX_LENGTH = 128

    fun isValid(key: String): Boolean = PATTERN.matches(key)
}
