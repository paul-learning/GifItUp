package app.gifitup.ui

import app.gifitup.shared.domain.gif.GifFailure

/** The fixed words of the GIF picker (#23). Nothing the provider sends is ever shown as text. */
internal object GifPickerTexts {
    const val TITLE = "Choose a GIF"
    const val CLOSE = "Close the GIF picker"

    /** Required by the provider's terms as the search field's placeholder (ADR-0003). */
    const val SEARCH_PLACEHOLDER = "Search KLIPY"
    const val SEARCH_LABEL = "Search GIFs"

    /** Said before anything is sent to the provider: who learns what (ADR-0003). */
    const val PRIVACY =
        "Your searches go to Klipy, and so does loading a GIF: Klipy sees your connection and what you search for. " +
            "Your chat itself stays end-to-end encrypted."
    const val TRENDING = "Trending"
    const val RESULTS = "Results"
    const val NOTHING_FOUND = "No GIFs found."
    const val LOADING = "Loading GIFs…"
    const val MORE = "Show more"
    const val TRY_AGAIN = "Try again"
    const val PLACEHOLDER = "This GIF can't be shown"
    const val CHOOSE = "Send this GIF"

    fun failure(
        reason: GifFailure,
        retryAfterSeconds: Int?,
    ): String =
        when (reason) {
            GifFailure.UNAVAILABLE -> "GIF search is temporarily unavailable."
            GifFailure.RATE_LIMITED -> "Too many searches right now. Try again in ${retryAfterSeconds ?: DEFAULT_WAIT_SECONDS} seconds."
            GifFailure.OFFLINE -> "GIF search can't be reached. Check your connection."
            GifFailure.MALFORMED -> "GIF search gave an answer this app can't use."
        }
}

private const val DEFAULT_WAIT_SECONDS = 60
