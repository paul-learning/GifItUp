package app.gifitup.ui

import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import app.gifitup.shared.domain.media.MediaInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.ByteBuffer

@Composable
internal actual fun AnimatedGif(
    data: ByteArray,
    info: MediaInfo,
    contentDescription: String,
    modifier: Modifier,
    onFailed: () -> Unit,
) {
    // Decoding is off the main thread; `null` until it is done, and for a file the decoder refuses.
    val decoded by produceState<Decoded?>(null, data) { value = withContext(Dispatchers.Default) { decode(data, info) } }
    val drawable = decoded?.drawable
    var frame by remember { mutableIntStateOf(0) }
    LaunchedEffect(decoded) { if (decoded?.drawable == null && decoded != null) onFailed() }
    DisposableEffect(drawable) {
        val handler = Handler(Looper.getMainLooper())
        // A drawable holds its callback weakly: this local, captured by onDispose, keeps it alive.
        val callback =
            object : Drawable.Callback {
                override fun invalidateDrawable(who: Drawable) {
                    frame++
                }

                // The animated drawable asks for its next frame to be run at a time (`SystemClock.uptimeMillis` based).
                override fun scheduleDrawable(
                    who: Drawable,
                    what: Runnable,
                    time: Long,
                ) = handler.postAtTime(what, who, time).let { }

                override fun unscheduleDrawable(
                    who: Drawable,
                    what: Runnable,
                ) = handler.removeCallbacks(what, who)
            }
        drawable?.callback = callback
        (drawable as? AnimatedImageDrawable)?.start()
        onDispose {
            (drawable as? AnimatedImageDrawable)?.stop()
            drawable?.callback = null
            handler.removeCallbacksAndMessages(drawable)
        }
    }
    if (drawable != null) {
        Canvas(modifier.semantics { this.contentDescription = contentDescription }) {
            frame // read, so every new frame draws again
            drawable.setBounds(0, 0, size.width.toInt(), size.height.toInt())
            drawIntoCanvas { drawable.draw(it.nativeCanvas) }
        }
    }
}

/** The result of a decode: [drawable] is `null` for a file the decoder refuses. */
private class Decoded(
    val drawable: Drawable?,
)

/**
 * The decoded drawable, or none for bytes the decoder refuses. The scanner saw the file first; the decoder must see the
 * same canvas, or the file is refused here (a parser difference must not get past the scanner's limits), and a picture
 * larger than [MAX_DECODED_SIDE] is decoded at a smaller size. The drawable reads [data] lazily: the tile's bytes are
 * never changed while it is shown.
 */
@Suppress("TooGenericExceptionCaught") // The decoder throws IOException and runtime errors for bad files alike.
private fun decode(
    data: ByteArray,
    info: MediaInfo,
): Decoded =
    try {
        val source = ImageDecoder.createSource(ByteBuffer.wrap(data))
        Decoded(
            ImageDecoder.decodeDrawable(source) { decoder, header, _ ->
                if (header.size.width != info.width || header.size.height != info.height) throw IOException("size")
                val scale = minOf(1f, MAX_DECODED_SIDE.toFloat() / maxOf(info.width, info.height))
                if (scale <
                    1f
                ) {
                    decoder.setTargetSize((info.width * scale).toInt().coerceAtLeast(1), (info.height * scale).toInt().coerceAtLeast(1))
                }
            },
        )
    } catch (ignored: Exception) {
        Decoded(null)
    } catch (ignored: OutOfMemoryError) {
        Decoded(null)
    }

private const val MAX_DECODED_SIDE = 640
