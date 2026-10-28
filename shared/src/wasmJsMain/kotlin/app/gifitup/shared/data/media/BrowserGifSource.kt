package app.gifitup.shared.data.media

import app.gifitup.shared.data.network.JsBytes
import app.gifitup.shared.data.network.toByteArray
import app.gifitup.shared.data.network.toJsBytes
import app.gifitup.shared.domain.media.MediaFormat
import kotlinx.coroutines.await
import kotlin.js.JsAny
import kotlin.js.Promise

/** One decoded frame: straight (not premultiplied) RGBA, row by row. [durationMillis] is `0` where the file says none. */
class BrowserFrame(
    val width: Int,
    val height: Int,
    val durationMillis: Int,
    val rgba: ByteArray,
)

/**
 * The frames of a scanned GIF or WebP, decoded by **the browser's own codecs** (GIF spike: browsers patch their codecs
 * themselves, Skiko's bundled ones would be ours to patch). WebCodecs' `ImageDecoder` gives every frame; where a browser
 * lacks it, `createImageBitmap` gives the first frame only (a still picture, [frameCount] is 1). Only decoded pixels
 * come back to Kotlin, never anything from the file's own metadata. Call [close] when the picture goes away.
 */
class BrowserGifSource private constructor(
    private val source: JsAny,
    val frameCount: Int,
) {
    /** Frame [index] (0 until [frameCount]), or `null` if the browser can't decode it. */
    suspend fun frame(index: Int): BrowserFrame? {
        val frame = jsFrame(source, index).await<JsAny?>() ?: return null
        return BrowserFrame(frameWidth(frame), frameHeight(frame), frameDuration(frame), frameData(frame).toByteArray())
    }

    fun close() = jsClose(source)

    companion object {
        /** `null` if the browser can't decode the file at all. */
        suspend fun open(
            data: ByteArray,
            format: MediaFormat,
        ): BrowserGifSource? {
            val source = jsOpen(data.toJsBytes(), mimeOf(format)).await<JsAny?>() ?: return null
            return BrowserGifSource(source, maxOf(sourceFrames(source), 1))
        }

        private fun mimeOf(format: MediaFormat): String =
            when (format) {
                MediaFormat.GIF -> "image/gif"
                MediaFormat.WEBP -> "image/webp"
            }
    }
}

@JsFun(
    """
async (bytes, type) => {
  try {
    if (typeof ImageDecoder === 'function' && await ImageDecoder.isTypeSupported(type)) {
      const decoder = new ImageDecoder({ data: bytes, type: type });
      await decoder.tracks.ready;
      return { decoder: decoder, bitmap: null, frames: decoder.tracks.selectedTrack.frameCount, canvas: document.createElement('canvas') };
    }
    const bitmap = await createImageBitmap(new Blob([bytes], { type: type }));
    return { decoder: null, bitmap: bitmap, frames: 1, canvas: document.createElement('canvas') };
  } catch (e) {
    return null;
  }
}
""",
)
private external fun jsOpen(
    bytes: JsBytes,
    type: String,
): Promise<JsAny?>

@JsFun(
    """
async (source, index) => {
  try {
    let image = source.bitmap;
    if (source.decoder !== null) image = (await source.decoder.decode({ frameIndex: index })).image;
    const width = image.displayWidth || image.width;
    const height = image.displayHeight || image.height;
    const duration = image.duration ? Math.round(image.duration / 1000) : 0;
    const canvas = source.canvas;
    canvas.width = width;
    canvas.height = height;
    const context = canvas.getContext('2d', { willReadFrequently: true });
    context.clearRect(0, 0, width, height);
    context.drawImage(image, 0, 0);
    if (source.decoder !== null) image.close();
    const pixels = context.getImageData(0, 0, width, height).data;
    return { width: width, height: height, duration: duration, data: new Uint8Array(pixels.buffer) };
  } catch (e) {
    return null;
  }
}
""",
)
private external fun jsFrame(
    source: JsAny,
    index: Int,
): Promise<JsAny?>

@JsFun("(source) => { if (source.decoder !== null) source.decoder.close(); else source.bitmap.close(); }")
private external fun jsClose(source: JsAny)

@JsFun("(source) => source.frames")
private external fun sourceFrames(source: JsAny): Int

@JsFun("(frame) => frame.width")
private external fun frameWidth(frame: JsAny): Int

@JsFun("(frame) => frame.height")
private external fun frameHeight(frame: JsAny): Int

@JsFun("(frame) => frame.duration")
private external fun frameDuration(frame: JsAny): Int

@JsFun("(frame) => frame.data")
private external fun frameData(frame: JsAny): JsBytes
