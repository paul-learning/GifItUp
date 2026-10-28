package app.gifitup.ui

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import app.gifitup.shared.domain.media.FrameCache
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
    // The pictures live as long as the composable shows them, also for a file with one frame that is played once.
    val frames = remember(data) { newFrameCache() }
    DisposableEffect(frames) { onDispose { frames.close() } }
    val still = prefersReducedMotion()
    LaunchedEffect(frames, still) {
        if (!play(data, info, frames, still) { current = it }) onFailed()
    }
    current?.let { Image(it, contentDescription, modifier, contentScale = ContentScale.Fit) }
}

/**
 * Shows the frames in turn, for ever, with the file's own delays; `false` if the browser can't decode the file or sees
 * another one than the scanner did. Frames are decoded at most [CACHE_BYTES] deep and then kept, so a short animation
 * is decoded once and a long one is decoded as it plays; the picture shown before is released as the next replaces it.
 * [FrameCache.close], when the composable leaves, releases the rest.
 */
@Suppress("TooGenericExceptionCaught") // The browser's failures arrive as several JavaScript error types.
private suspend fun play(
    data: ByteArray,
    info: MediaInfo,
    frames: FrameCache<Shown>,
    still: Boolean,
    show: (ImageBitmap) -> Unit,
): Boolean {
    val source = BrowserGifSource.open(data, info) ?: return false
    return try {
        showFrames(source, frames, still, show)
    } catch (e: CancellationException) {
        throw e
    } catch (ignored: Throwable) {
        false
    } finally {
        source.close()
    }
}

private suspend fun showFrames(
    source: BrowserGifSource,
    frames: FrameCache<Shown>,
    still: Boolean,
    show: (ImageBitmap) -> Unit,
): Boolean {
    var index = 0
    while (true) {
        // A tab in the background does not decode.
        while (tabHidden()) delay(HIDDEN_POLL_MS)
        val shown = frames.get(index) ?: source.frame(index)?.let { frames.put(index, wrap(it), it.rgba.size) } ?: return false
        show(shown.image)
        if (source.frameCount == 1 || still) return true
        delay(GifDelay.millis(shown.durationMillis))
        index = (index + 1) % source.frameCount
    }
}

/** A decoded frame ready to draw; [skia] is the image behind [image], released with the cache. */
private class Shown(
    val skia: Skia,
    val image: ImageBitmap,
    val durationMillis: Int,
)

/** Wraps [frame] for drawing; the Skia image is closed if the wrapping fails. */
private fun wrap(frame: BrowserFrame): Shown {
    val skia = frame.toSkiaImage()
    var wrapped = false
    return try {
        Shown(skia, skia.toComposeImageBitmap(), frame.durationMillis).also { wrapped = true }
    } finally {
        if (!wrapped) skia.close()
    }
}

private fun newFrameCache() = FrameCache<Shown>(CACHE_BYTES) { it.skia.close() }

private typealias Skia = org.jetbrains.skia.Image

private fun BrowserFrame.toSkiaImage(): Skia =
    Skia.makeRaster(ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL), rgba, width * BYTES_PER_PIXEL)

@JsFun("() => document.hidden === true")
private external fun tabHidden(): Boolean

private const val CACHE_BYTES = 24 * 1024 * 1024
private const val HIDDEN_POLL_MS = 500L

private const val BYTES_PER_PIXEL = 4
