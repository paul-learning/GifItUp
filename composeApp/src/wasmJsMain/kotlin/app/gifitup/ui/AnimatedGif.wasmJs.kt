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
import app.gifitup.shared.domain.media.MediaFormat
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
    format: MediaFormat,
    contentDescription: String,
    modifier: Modifier,
    onFailed: () -> Unit,
) {
    var current by remember(data) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(data) {
        if (!play(data, format) { current = it }) onFailed()
    }
    current?.let { Image(it, contentDescription, modifier, contentScale = ContentScale.Fit) }
}

/** Shows the frames in turn, for ever, with the file's own delays; `false` if the browser can't decode the file. */
@Suppress("TooGenericExceptionCaught") // The browser's failures arrive as several JavaScript error types.
private suspend fun play(
    data: ByteArray,
    format: MediaFormat,
    show: (ImageBitmap) -> Unit,
): Boolean {
    val source = BrowserGifSource.open(data, format) ?: return false
    return try {
        showFrames(source, show)
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
    show: (ImageBitmap) -> Unit,
): Boolean {
    var index = 0
    while (true) {
        val frame = source.frame(index) ?: return false
        show(frame.toImageBitmap())
        if (source.frameCount == 1) return true
        delay(delayFor(frame.durationMillis))
        index = (index + 1) % source.frameCount
    }
}

private fun BrowserFrame.toImageBitmap(): ImageBitmap =
    org.jetbrains.skia.Image
        .makeRaster(ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL), rgba, width * BYTES_PER_PIXEL)
        .toComposeImageBitmap()

/** Browsers show a GIF delay of 10 ms or less as 100 ms; so does this. */
private fun delayFor(milliseconds: Int): Long = if (milliseconds <= SHORTEST_DELAY_MS) DEFAULT_DELAY_MS else milliseconds.toLong()

private const val BYTES_PER_PIXEL = 4
private const val SHORTEST_DELAY_MS = 10
private const val DEFAULT_DELAY_MS = 100L
