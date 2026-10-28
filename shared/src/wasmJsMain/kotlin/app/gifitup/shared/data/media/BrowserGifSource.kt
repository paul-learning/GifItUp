package app.gifitup.shared.data.media

import app.gifitup.shared.data.network.JsBytes
import app.gifitup.shared.data.network.toByteArray
import app.gifitup.shared.data.network.toJsBytes
import app.gifitup.shared.domain.media.MediaFormat
import app.gifitup.shared.domain.media.MediaInfo
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
        /**
         * `null` if the browser can't decode the file, or sees it differently from the scanner ([info]: another canvas
         * size, more frames), so a parser difference can't get past the limits the scanner enforced. Frames larger than
         * [MAX_FRAME_SIDE] come back scaled down to it.
         */
        suspend fun open(
            data: ByteArray,
            info: MediaInfo,
        ): BrowserGifSource? {
            val source = jsOpen(data.toJsBytes(), mimeOf(info.format), info.width, info.height, info.frames, MAX_FRAME_SIDE).await<JsAny?>()
            return source?.let { BrowserGifSource(it, maxOf(sourceFrames(it), 1)) }
        }

        const val MAX_FRAME_SIDE = 640

        private fun mimeOf(format: MediaFormat): String =
            when (format) {
                MediaFormat.GIF -> "image/gif"
                MediaFormat.WEBP -> "image/webp"
            }
    }
}

@JsFun(
    """
async (bytes, type, width, height, frames, maxSide) => {
  let decoder = null;
  let bitmap = null;
  try {
    const canvas = document.createElement('canvas');
    if (typeof ImageDecoder === 'function' && await ImageDecoder.isTypeSupported(type)) {
      decoder = new ImageDecoder({ data: bytes, type: type });
      await decoder.tracks.ready;
      const count = decoder.tracks.selectedTrack.frameCount;
      if (count < 1 || count > frames) throw new Error('frames');
      return { decoder: decoder, bitmap: null, frames: count, canvas: canvas, width: width, height: height, maxSide: maxSide };
    }
    bitmap = await createImageBitmap(new Blob([bytes], { type: type }));
    if (bitmap.width !== width || bitmap.height !== height) throw new Error('size');
    return { decoder: null, bitmap: bitmap, frames: 1, canvas: canvas, width: width, height: height, maxSide: maxSide };
  } catch (e) {
    if (decoder !== null) decoder.close();
    if (bitmap !== null) bitmap.close();
    return null;
  }
}
""",
)
@Suppress("LongParameterList") // The scanner's facts about the file, passed to the browser side as they are.
private external fun jsOpen(
    bytes: JsBytes,
    type: String,
    width: Int,
    height: Int,
    frames: Int,
    maxSide: Int,
): Promise<JsAny?>

@JsFun(
    """
async (source, index) => {
  let image = null;
  try {
    image = source.bitmap;
    if (source.decoder !== null) image = (await source.decoder.decode({ frameIndex: index })).image;
    const width = image.displayWidth || image.width;
    const height = image.displayHeight || image.height;
    // What the browser decoded must be what the scanner saw.
    if (width !== source.width || height !== source.height) return null;
    const duration = image.duration ? Math.round(image.duration / 1000) : 0;
    const scale = Math.min(1, source.maxSide / Math.max(width, height));
    const shownWidth = Math.max(1, Math.round(width * scale));
    const shownHeight = Math.max(1, Math.round(height * scale));
    const canvas = source.canvas;
    canvas.width = shownWidth;
    canvas.height = shownHeight;
    const context = canvas.getContext('2d', { willReadFrequently: true });
    context.clearRect(0, 0, shownWidth, shownHeight);
    context.drawImage(image, 0, 0, shownWidth, shownHeight);
    const pixels = context.getImageData(0, 0, shownWidth, shownHeight).data;
    return { width: shownWidth, height: shownHeight, duration: duration, data: new Uint8Array(pixels.buffer) };
  } catch (e) {
    return null;
  } finally {
    if (source.decoder !== null && image !== null) image.close();
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
