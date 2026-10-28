package app.gifitup.ui

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import app.gifitup.shared.data.media.BrowserFrame
import app.gifitup.shared.data.media.BrowserGifSource
import app.gifitup.shared.domain.media.GifDelay
import app.gifitup.shared.domain.media.MediaInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo

/**
 * The browser decodes (its codecs are patched with the browser, GIF spike); Skia only draws the finished pixels. A
 * browser without WebCodecs' `ImageDecoder` shows the first frame as a still picture.
 */
@Composable
internal actual fun AnimatedGif(
    data: ByteArray,
    info: MediaInfo,
    contentDescription: String,
    modifier: Modifier,
    onFailed: () -> Unit,
) {
    var current by remember(data) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(data) {
        try {
            if (!play(data, info) { current = it }) onFailed()
        } finally {
            // The images behind it are released with the play; nothing may draw one that is closed.
            current = null
        }
    }
    current?.let { Image(it, contentDescription, modifier, contentScale = ContentScale.Fit) }
}

/**
 * Shows the frames in turn, for ever, with the file's own delays; `false` if the browser can't decode the file or sees
 * another one than the scanner did. Frames are decoded at most [CACHE_BYTES] deep and then kept, so a short animation
 * is decoded once and a long one is decoded as it plays; the picture shown before is released as the next replaces it.
 */
@Suppress("TooGenericExceptionCaught") // The browser's failures arrive as several JavaScript error types.
private suspend fun play(
    data: ByteArray,
    info: MediaInfo,
    show: (ImageBitmap) -> Unit,
): Boolean {
    val source = BrowserGifSource.open(data, info) ?: return false
    val frames = FrameCache()
    return try {
        showFrames(source, frames, show)
    } catch (e: CancellationException) {
        throw e
    } catch (ignored: Throwable) {
        false
    } finally {
        source.close()
        frames.close()
    }
}

private suspend fun showFrames(
    source: BrowserGifSource,
    frames: FrameCache,
    show: (ImageBitmap) -> Unit,
): Boolean {
    var index = 0
    while (true) {
        // A tab in the background does not decode.
        while (tabHidden()) delay(HIDDEN_POLL_MS)
        val shown = frames.get(index) ?: source.frame(index)?.let { frames.put(index, it) } ?: return false
        show(shown.image)
        if (source.frameCount == 1) return true
        delay(GifDelay.millis(shown.durationMillis))
        index = (index + 1) % source.frameCount
    }
}

/** A decoded frame ready to draw. */
private class Shown(
    val image: ImageBitmap,
    val durationMillis: Int,
    val bytes: Int,
)

/** Frames kept for the next loop, up to [CACHE_BYTES]; one over it is shown and released when the next replaces it. */
private class FrameCache {
    private val kept = HashMap<Int, Shown>()
    private var size = 0
    private var uncached: Skia? = null

    fun get(index: Int): Shown? = kept[index]

    fun put(
        index: Int,
        frame: BrowserFrame,
    ): Shown {
        val skia = frame.toSkiaImage()
        val shown = Shown(skia.toComposeImageBitmap(), frame.durationMillis, frame.rgba.size)
        if (size + shown.bytes <= CACHE_BYTES) {
            kept[index] = shown
            size += shown.bytes
            skiaImages.add(skia)
        } else {
            uncached?.close()
            uncached = skia
        }
        return shown
    }

    fun close() {
        uncached?.close()
        skiaImages.forEach(Skia::close)
        kept.clear()
    }

    private val skiaImages = ArrayList<Skia>()
}

private typealias Skia = org.jetbrains.skia.Image

private fun BrowserFrame.toSkiaImage(): Skia =
    Skia.makeRaster(ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL), rgba, width * BYTES_PER_PIXEL)

@JsFun("() => document.hidden === true")
private external fun tabHidden(): Boolean

private const val CACHE_BYTES = 24 * 1024 * 1024
private const val HIDDEN_POLL_MS = 500L

private const val BYTES_PER_PIXEL = 4
