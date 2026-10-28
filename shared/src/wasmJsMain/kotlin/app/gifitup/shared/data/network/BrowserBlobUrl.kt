package app.gifitup.shared.data.network

import app.gifitup.shared.domain.media.MediaFormat
import app.gifitup.shared.domain.media.MediaInfo

/**
 * A `blob:` URL for bytes that passed the scanner, so the browser decodes them (GIF spike: the browser's own codecs,
 * not Skiko's) and a page never has an `<img>` that points at the provider. The type comes from the scan, not from the
 * server. Revoke the URL when the picture goes away.
 */
object BrowserBlobUrl {
    fun create(
        data: ByteArray,
        info: MediaInfo,
    ): String = createObjectUrl(data.toUint8Array(), mimeOf(info.format))

    fun revoke(url: String) = revokeObjectUrl(url)

    private fun mimeOf(format: MediaFormat): String =
        when (format) {
            MediaFormat.GIF -> "image/gif"
            MediaFormat.WEBP -> "image/webp"
        }
}

private const val BYTE_MASK = 0xFF

private fun ByteArray.toUint8Array(): JsBytes {
    val out = newJsBytes(size)
    for (index in indices) jsSetByte(out, index, this[index].toInt() and BYTE_MASK)
    return out
}

@JsFun("(bytes, type) => URL.createObjectURL(new Blob([bytes], { type: type }))")
private external fun createObjectUrl(
    bytes: JsBytes,
    type: String,
): String

@JsFun("(url) => URL.revokeObjectURL(url)")
private external fun revokeObjectUrl(url: String)
