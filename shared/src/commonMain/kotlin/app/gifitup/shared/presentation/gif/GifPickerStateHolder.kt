package app.gifitup.shared.presentation.gif

import app.gifitup.shared.domain.gif.GifFailure
import app.gifitup.shared.domain.gif.GifProvider
import app.gifitup.shared.domain.gif.GifResult
import app.gifitup.shared.domain.gif.GifTile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the GIF picker shows (#23). [tiles] are the provider's results in its order; nothing of them is filtered by us. */
data class GifPickerState(
    /** What is in the search field; blank means trending. */
    val query: String = "",
    val tiles: List<GifTile> = emptyList(),
    val hasNext: Boolean = false,
    /** A first page or a next page is on its way. */
    val loading: Boolean = false,
    /** The last call's failure; earlier tiles stay. Cleared when a call starts. */
    val failure: GifFailure? = null,
    val retryAfterSeconds: Int? = null,
    /** True once the first answer (or failure) of the current query arrived. */
    val loaded: Boolean = false,
)

/**
 * The state of the GIF picker (#23): trending when the field is blank, a search (debounced, newer text cancels the older
 * call and drops its answer) otherwise, next pages on request. It asks the [GifProvider] and nothing else: no key, no
 * server (the key is the refresher's), and **no automatic retry**: after a failure or a rate limit the user taps "try
 * again". Like the other holders it is driven from one thread (the UI's main scope).
 */
class GifPickerStateHolder(
    private val scope: CoroutineScope,
    private val provider: GifProvider,
    private val debounceMillis: Long = DEBOUNCE_MILLIS,
) {
    private val flow = MutableStateFlow(GifPickerState())
    val state: StateFlow<GifPickerState> = flow.asStateFlow()
    private var job: Job? = null
    private var page = 0

    /** The picker opened: trending, unless it already shows something. */
    fun open() {
        if (!flow.value.loaded && !flow.value.loading) startFirstPage(delayMillis = 0)
    }

    /** The text of the search field changed. */
    fun onQuery(text: String) {
        val query = text.take(MAX_QUERY)
        if (query == flow.value.query) return
        flow.update { it.copy(query = query) }
        // Typing waits for a pause; clearing the field goes to trending at once.
        startFirstPage(delayMillis = if (query.isBlank()) 0 else debounceMillis)
    }

    fun loadMore() {
        val now = flow.value
        if (!now.hasNext || now.loading || now.failure != null) return
        launchCall(now.query, page + 1, delayMillis = 0)
    }

    /** "Try again" after a failure: the same call once more, at the user's request. */
    fun retry() {
        val now = flow.value
        if (now.loading) return
        if (now.tiles.isEmpty() || page == 0) startFirstPage(delayMillis = 0) else launchCall(now.query, page + 1, delayMillis = 0)
    }

    /** The picker closed: a call in flight stops and nothing of it is kept. */
    fun close() {
        job?.cancel()
        job = null
        page = 0
        flow.value = GifPickerState()
    }

    private fun startFirstPage(delayMillis: Long) {
        page = 0
        flow.update { it.copy(tiles = emptyList(), hasNext = false, failure = null, retryAfterSeconds = null, loaded = false) }
        launchCall(flow.value.query, 1, delayMillis)
    }

    private fun launchCall(
        query: String,
        pageNumber: Int,
        delayMillis: Long,
    ) {
        job?.cancel()
        flow.update { it.copy(loading = true, failure = null, retryAfterSeconds = null) }
        // Lazy, so the job is registered before it can run, even on a dispatcher that runs it at once.
        val mine =
            scope.launch(start = CoroutineStart.LAZY) {
                if (delayMillis > 0) delay(delayMillis)
                val result = if (query.isBlank()) provider.trending(pageNumber) else provider.search(query.trim(), pageNumber)
                // Only the call that is still current may answer.
                if (job === coroutineContext[Job]) apply(result, pageNumber)
            }
        job = mine
        mine.start()
    }

    private fun apply(
        result: GifResult,
        pageNumber: Int,
    ) {
        when (result) {
            is GifResult.Page -> {
                page = pageNumber
                flow.update { it.copy(tiles = it.tiles + result.tiles, hasNext = result.hasNext, loading = false, loaded = true) }
            }

            is GifResult.Failed -> {
                flow.update {
                    it.copy(
                        loading = false,
                        failure = result.reason,
                        retryAfterSeconds = result.retryAfterSeconds,
                        loaded = true,
                    )
                }
            }
        }
    }

    companion object {
        /** The issue asks for at least 400 ms between the last keystroke and the call. */
        const val DEBOUNCE_MILLIS = 450L
        const val MAX_QUERY = 100
    }
}
