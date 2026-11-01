package app.gifitup.shared.domain.gif

import app.gifitup.shared.data.content.GifReference

/**
 * Finds GIFs at a provider (ADR-0003): straight from the client, never through our server. The interface knows no provider
 * name and no URL string; the adapter (`data/gif`) does. Results come in the provider's order and are not filtered by us.
 */
interface GifProvider {
    suspend fun trending(page: Int = 1): GifResult

    suspend fun search(
        query: String,
        page: Int = 1,
    ): GifResult
}

sealed interface GifResult {
    class Page(
        val tiles: List<GifTile>,
        val hasNext: Boolean,
    ) : GifResult

    /** [retryAfterSeconds] is set for [GifFailure.RATE_LIMITED]: no call is made before then. */
    class Failed(
        val reason: GifFailure,
        val retryAfterSeconds: Int? = null,
    ) : GifResult
}

/** Closed, so nothing of an error (a URL with the key in it, a message, a header) can travel with it. */
enum class GifFailure {
    /** No key yet, or the provider refused it (401/403), or answered with a server error: "temporarily unavailable". */
    UNAVAILABLE,

    /** 429, or this client's own guard against hammering a limit that every friend shares. */
    RATE_LIMITED,

    /** The request did not get an answer. */
    OFFLINE,

    /** An answer that breaks the rules: too big, not JSON, not the expected shape. */
    MALFORMED,
}

/** One result, in the provider's order. A placeholder carries no text and no URL at all. */
sealed interface GifTile {
    /** [preview] is for the picker grid, [send] is what goes into the message. Both passed [GifReference.of]. */
    class Item(
        val preview: GifReference,
        val send: GifReference,
    ) : GifTile {
        override fun toString(): String = "GifTile.Item"
    }

    /** A result that failed the rules, an ad, or anything that is not a GIF: shown inert, nothing loaded. */
    data object Placeholder : GifTile
}
