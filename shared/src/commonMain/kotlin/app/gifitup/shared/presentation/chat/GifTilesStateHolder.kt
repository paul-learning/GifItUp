package app.gifitup.shared.presentation.chat

import app.gifitup.shared.data.content.GifReference
import app.gifitup.shared.domain.contacts.KeyBytes
import app.gifitup.shared.domain.media.Autoload
import app.gifitup.shared.domain.media.LoadGifMedia
import app.gifitup.shared.domain.media.MediaInfo
import app.gifitup.shared.domain.media.MediaLoad
import app.gifitup.shared.domain.media.MediaPolicy
import app.gifitup.shared.domain.media.UnavailableReason
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** What a GIF bubble shows. The media itself is never kept after the bubble is gone ([GifTilesStateHolder.hide]). */
sealed interface GifTile {
    /** Not loaded on its own: this device's codecs are not patched (ADR-0003), so it waits for a tap and says why. */
    data object TapToLoad : GifTile

    data object Loading : GifTile

    /** [data] passed the scanner; only the platform decoder is given it. */
    class Ready(
        val data: ByteArray,
        val info: MediaInfo,
    ) : GifTile

    /** A placeholder; a tap tries again. */
    class Unavailable(
        val reason: UnavailableReason,
    ) : GifTile
}

/**
 * The media of the GIF bubbles on screen (#25, ADR-0003): a bubble that appears asks to be [show]n, one that leaves
 * [hide]s, so only what is on screen holds bytes. On a device whose codecs [MediaPolicy] distrusts nothing is fetched
 * until the user taps. At most [parallel] downloads run at once. An answer for a bubble that has left, or for an
 * older visit, is dropped. Like the other holders it is driven from one thread (the UI's main scope).
 */
class GifTilesStateHolder(
    private val scope: CoroutineScope,
    private val policy: MediaPolicy,
    private val load: LoadGifMedia,
    parallel: Int = DEFAULT_PARALLEL,
) {
    private val flow = MutableStateFlow<Map<KeyBytes, GifTile>>(emptyMap())
    val tiles: StateFlow<Map<KeyBytes, GifTile>> = flow.asStateFlow()
    private val jobs = HashMap<KeyBytes, Job>()
    private val slots = Semaphore(parallel)

    /** Whether tiles load on their own on this device; the screen explains a [GifTile.TapToLoad] with it. */
    val autoload: Autoload get() = policy.autoload()

    /** A bubble appeared: loads it, or waits for a tap. A bubble that already has a tile keeps it. */
    fun show(
        key: KeyBytes,
        reference: GifReference,
    ) {
        if (key in flow.value) return
        if (policy.autoload() == Autoload.TAP_TO_LOAD) set(key, GifTile.TapToLoad) else start(key, reference)
    }

    /** The user tapped a bubble that waits or failed. */
    fun tap(
        key: KeyBytes,
        reference: GifReference,
    ) {
        val tile = flow.value[key]
        if (tile is GifTile.TapToLoad || tile is GifTile.Unavailable) start(key, reference)
    }

    /** The bubble left the screen: its download stops and its bytes are dropped. */
    fun hide(key: KeyBytes) {
        jobs.remove(key)?.cancel()
        flow.update { it - key }
    }

    fun leave() {
        jobs.values.forEach(Job::cancel)
        jobs.clear()
        flow.value = emptyMap()
    }

    private fun start(
        key: KeyBytes,
        reference: GifReference,
    ) {
        jobs.remove(key)?.cancel()
        set(key, GifTile.Loading)
        // Lazy, so the job is registered before it can run, even on a dispatcher that runs it at once.
        val job =
            scope.launch(start = CoroutineStart.LAZY) {
                val loaded = slots.withPermit { load(reference) }
                // Only the job that is still the bubble's own may answer.
                if (jobs[key] === coroutineContext[Job]) {
                    jobs.remove(key)
                    set(key, loaded.toTile())
                }
            }
        jobs[key] = job
        job.start()
    }

    private fun set(
        key: KeyBytes,
        tile: GifTile,
    ) {
        flow.update { it + (key to tile) }
    }

    private companion object {
        // A tile that scrolled away keeps its slot until its request ends (a request on the wire is never cancelled on Android:
        // see KtorMediaFetcher.fetch), at most one timeout; with six slots the visible tiles don't wait long behind such leftovers.
        const val DEFAULT_PARALLEL = 6
    }
}

private fun MediaLoad.toTile(): GifTile =
    when (this) {
        is MediaLoad.Ready -> GifTile.Ready(data, info)
        is MediaLoad.Unavailable -> GifTile.Unavailable(reason)
    }
