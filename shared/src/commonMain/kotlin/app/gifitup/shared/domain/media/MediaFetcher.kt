package app.gifitup.shared.domain.media

import app.gifitup.shared.data.content.GifReference

/** How a download ended. Closed: nothing of the response (headers, body text, errors) is kept. */
sealed interface FetchResult {
    class Bytes(
        val data: ByteArray,
    ) : FetchResult

    /** Any answer but a complete `200`: no network, a redirect, an error status, a timeout. */
    data object Failed : FetchResult

    /** More than [MediaScanner.MAX_BYTES], by the declared length or by what arrived. */
    data object TooLarge : FetchResult
}

/**
 * Downloads the media of a validated [GifReference] straight from the provider (ADR-0003: never through our server).
 * The request carries no cookies and no `Referer`, follows no redirect, and stops at [MediaScanner.MAX_BYTES].
 */
fun interface MediaFetcher {
    suspend fun fetch(reference: GifReference): FetchResult
}
