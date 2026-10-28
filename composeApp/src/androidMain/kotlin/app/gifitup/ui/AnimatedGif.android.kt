package app.gifitup.ui

import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import app.gifitup.shared.domain.media.MediaFormat
import java.nio.ByteBuffer

@Composable
internal actual fun AnimatedGif(
    data: ByteArray,
    format: MediaFormat,
    contentDescription: String,
    modifier: Modifier,
    onFailed: () -> Unit,
) {
    val drawable = remember(data) { decode(data) }
    var frame by remember { mutableIntStateOf(0) }
    LaunchedEffect(drawable) { if (drawable == null) onFailed() }
    DisposableEffect(drawable) {
        // A drawable holds its callback weakly: this local, captured by onDispose, keeps it alive.
        val callback =
            object : Drawable.Callback {
                override fun invalidateDrawable(who: Drawable) {
                    frame++
                }

                override fun scheduleDrawable(
                    who: Drawable,
                    what: Runnable,
                    time: Long,
                ) = Unit

                override fun unscheduleDrawable(
                    who: Drawable,
                    what: Runnable,
                ) = Unit
            }
        drawable?.callback = callback
        (drawable as? AnimatedImageDrawable)?.start()
        onDispose {
            (drawable as? AnimatedImageDrawable)?.stop()
            drawable?.callback = null
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

/** The decoded drawable, or `null` for bytes the decoder refuses (the scanner let them through, the codec did not). */
@Suppress("TooGenericExceptionCaught") // The decoder throws IOException and runtime errors for bad files alike.
private fun decode(data: ByteArray): Drawable? =
    try {
        ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(data)))
    } catch (ignored: Exception) {
        null
    }
