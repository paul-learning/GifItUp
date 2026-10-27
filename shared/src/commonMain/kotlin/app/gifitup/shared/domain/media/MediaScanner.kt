@file:Suppress("MagicNumber") // Byte offsets and sizes of the GIF and WebP formats, named where it helps.

package app.gifitup.shared.domain.media

enum class MediaFormat { GIF, WEBP }

/** What the file itself says (never the sender's hints): the canvas and the number of frames. */
class MediaInfo(
    val format: MediaFormat,
    val width: Int,
    val height: Int,
    val frames: Int,
)

/** Why a file is not shown. Closed; no variant carries anything from the file. */
enum class Rejection { UNKNOWN_FORMAT, MALFORMED, CANVAS_TOO_LARGE, TOO_MANY_FRAMES, TOO_MUCH_MEMORY, TOO_COMPLEX }

sealed interface ScanResult {
    class Accepted(
        val info: MediaInfo,
    ) : ScanResult

    class Rejected(
        val reason: Rejection,
    ) : ScanResult
}

/**
 * A bounded, pure-Kotlin walk over a downloaded GIF or WebP before any decoder sees it (ADR-0003, GIF spike): it reads
 * the real canvas size, counts frames by walking the GIF blocks or the RIFF `ANMF` chunks, and checks the limits.
 * Anything malformed, truncated or over a limit is rejected. It never throws, whatever the bytes are, and takes a
 * bounded number of steps.
 *
 * **What it is not:** it does not make decoding safe. It stops resource exhaustion and malformed containers; a bug
 * inside the compressed image data (the libwebp CVE-2023-4863 class) passes it unchanged (threat model).
 */
object MediaScanner {
    const val MAX_BYTES = 8 * 1024 * 1024
    const val MAX_SIDE = 2048
    const val MAX_FRAMES = 1000
    const val MAX_DECODED_BYTES = 128L * 1024 * 1024
    const val MAX_STEPS = 500_000

    fun scan(data: ByteArray): ScanResult =
        try {
            if (data.size > MAX_BYTES) reject(Rejection.MALFORMED)
            val info =
                when {
                    data.startsWithAscii("GIF87a") || data.startsWithAscii("GIF89a") -> GifWalker(data).walk()
                    data.startsWithAscii("RIFF") && data.asciiAt(8, "WEBP") -> WebpWalker(data).walk()
                    else -> reject(Rejection.UNKNOWN_FORMAT)
                }
            checkLimits(info)
            ScanResult.Accepted(info)
        } catch (stop: Stop) {
            ScanResult.Rejected(stop.reason)
        }

    private fun checkLimits(info: MediaInfo) {
        if (info.width !in 1..MAX_SIDE || info.height !in 1..MAX_SIDE) reject(Rejection.CANVAS_TOO_LARGE)
        if (info.frames !in 1..MAX_FRAMES) reject(Rejection.TOO_MANY_FRAMES)
        if (info.width.toLong() * info.height * BYTES_PER_PIXEL * info.frames > MAX_DECODED_BYTES) reject(Rejection.TOO_MUCH_MEMORY)
    }

    private const val BYTES_PER_PIXEL = 4

    /** Unwinds out of a walk; caught only in [scan]. */
    internal class Stop(
        val reason: Rejection,
    ) : Exception()

    internal fun reject(reason: Rejection): Nothing = throw Stop(reason)
}

private fun ByteArray.startsWithAscii(text: String): Boolean = asciiAt(0, text)

private fun ByteArray.asciiAt(
    offset: Int,
    text: String,
): Boolean = size >= offset + text.length && text.indices.all { this[offset + it] == text[it].code.toByte() }

/** Reads little-endian numbers; any read outside the data is a malformed file. */
internal class Cursor(
    private val data: ByteArray,
) {
    val size: Int get() = data.size

    fun u8(at: Int): Int {
        if (at < 0 || at >= data.size) MediaScanner.reject(Rejection.MALFORMED)
        return data[at].toInt() and 0xFF
    }

    fun u16(at: Int): Int = u8(at) or (u8(at + 1) shl 8)

    fun u24(at: Int): Int = u16(at) or (u8(at + 2) shl 16)

    fun u32(at: Int): Long = u24(at).toLong() or (u8(at + 3).toLong() shl 24)

    fun ascii(
        at: Int,
        length: Int,
    ): String = CharArray(length) { u8(at + it).toChar() }.concatToString()
}

/** Counts steps so a crafted file can't make a walk long. */
internal class StepBudget {
    private var steps = 0

    fun step() {
        if (++steps > MediaScanner.MAX_STEPS) MediaScanner.reject(Rejection.TOO_COMPLEX)
    }
}

private class GifWalker(
    data: ByteArray,
) {
    private val bytes = Cursor(data)
    private val budget = StepBudget()
    private var position = 0

    fun walk(): MediaInfo {
        val width = bytes.u16(WIDTH_AT)
        val height = bytes.u16(WIDTH_AT + 2)
        position = HEADER_SIZE + colorTableSize(bytes.u8(FLAGS_AT))
        var frames = 0
        while (true) {
            budget.step()
            when (bytes.u8(position++)) {
                EXTENSION -> {
                    position++
                    skipSubBlocks()
                }

                IMAGE -> {
                    imageDescriptor()
                    if (++frames > MediaScanner.MAX_FRAMES) MediaScanner.reject(Rejection.TOO_MANY_FRAMES)
                }

                TRAILER -> {
                    return finish(width, height, frames)
                }

                else -> {
                    MediaScanner.reject(Rejection.MALFORMED)
                }
            }
        }
    }

    private fun finish(
        width: Int,
        height: Int,
        frames: Int,
    ): MediaInfo {
        if (frames == 0 || width == 0 || height == 0) MediaScanner.reject(Rejection.MALFORMED)
        return MediaInfo(MediaFormat.GIF, width, height, frames)
    }

    /** Left, top, width, height (2 bytes each), the flags, an optional local color table, the LZW size and the data. */
    private fun imageDescriptor() {
        position += DESCRIPTOR_FIELDS
        val flags = bytes.u8(position++)
        position += colorTableSize(flags)
        val lzwSize = bytes.u8(position++)
        if (lzwSize !in 1..MAX_LZW_SIZE) MediaScanner.reject(Rejection.MALFORMED)
        skipSubBlocks()
    }

    /** The size of a color table announced by [flags] (bit 7: present; bits 0-2: size). */
    private fun colorTableSize(flags: Int): Int = if (flags and TABLE_FLAG != 0) 3 shl ((flags and TABLE_BITS) + 1) else 0

    private fun skipSubBlocks() {
        while (true) {
            budget.step()
            val size = bytes.u8(position++)
            if (size == 0) return
            position += size
            if (position > bytes.size) MediaScanner.reject(Rejection.MALFORMED)
        }
    }

    private companion object {
        const val WIDTH_AT = 6
        const val FLAGS_AT = 10
        const val HEADER_SIZE = 13
        const val DESCRIPTOR_FIELDS = 8
        const val EXTENSION = 0x21
        const val IMAGE = 0x2C
        const val TRAILER = 0x3B
        const val TABLE_FLAG = 0x80
        const val TABLE_BITS = 0x07
        const val MAX_LZW_SIZE = 11
    }
}

private class WebpWalker(
    data: ByteArray,
) {
    private val bytes = Cursor(data)
    private val budget = StepBudget()
    private var width = 0
    private var height = 0
    private var animated = false
    private var frames = 0
    private var sawImage = false

    fun walk(): MediaInfo {
        val end = RIFF_HEADER + bytes.u32(RIFF_SIZE_AT)
        if (end > bytes.size || end < FIRST_CHUNK) MediaScanner.reject(Rejection.MALFORMED)
        var position = FIRST_CHUNK
        var first = true
        while (position < end) {
            budget.step()
            if (position + CHUNK_HEADER > end) MediaScanner.reject(Rejection.MALFORMED)
            val type = bytes.ascii(position, FOURCC)
            val size = bytes.u32(position + FOURCC)
            val payload = position + CHUNK_HEADER
            if (payload + size > end) MediaScanner.reject(Rejection.MALFORMED)
            if (first) canvasOf(type, payload, size) else laterChunk(type, payload, size)
            first = false
            position = (payload + size + (size and 1)).toInt()
        }
        if (!complete()) MediaScanner.reject(Rejection.MALFORMED)
        return MediaInfo(MediaFormat.WEBP, width, height, if (animated) frames else 1)
    }

    /** A canvas, and frames for an animation or an image chunk for a still. */
    private fun complete(): Boolean = width != 0 && if (animated) frames > 0 else sawImage

    /** The first chunk says how big the picture is: `VP8X` (extended), `VP8 ` (lossy) or `VP8L` (lossless). */
    private fun canvasOf(
        type: String,
        payload: Int,
        size: Long,
    ) {
        when (type) {
            "VP8X" -> {
                if (size < VP8X_SIZE) MediaScanner.reject(Rejection.MALFORMED)
                animated = bytes.u8(payload) and ANIMATION_FLAG != 0
                width = bytes.u24(payload + CANVAS_AT) + 1
                height = bytes.u24(payload + CANVAS_AT + 3) + 1
            }

            "VP8 " -> {
                if (size < LOSSY_SIZE || bytes.u24(payload + 3) != START_CODE) MediaScanner.reject(Rejection.MALFORMED)
                width = bytes.u16(payload + 6) and DIMENSION_MASK
                height = bytes.u16(payload + 8) and DIMENSION_MASK
                sawImage = true
            }

            "VP8L" -> {
                if (size < LOSSLESS_SIZE || bytes.u8(payload) != LOSSLESS_SIGNATURE) MediaScanner.reject(Rejection.MALFORMED)
                val bits = bytes.u32(payload + 1)
                width = (bits and DIMENSION_MASK.toLong()).toInt() + 1
                height = ((bits shr DIMENSION_BITS) and DIMENSION_MASK.toLong()).toInt() + 1
                sawImage = true
            }

            else -> {
                MediaScanner.reject(Rejection.MALFORMED)
            }
        }
    }

    private fun laterChunk(
        type: String,
        payload: Int,
        size: Long,
    ) {
        when (type) {
            "ANMF" -> frame(payload, size)
            "VP8 ", "VP8L" -> sawImage = true
        }
    }

    /** An animation frame: its rectangle (x and y in units of two pixels) must lie inside the canvas. */
    private fun frame(
        payload: Int,
        size: Long,
    ) {
        if (!animated || size < FRAME_HEADER) MediaScanner.reject(Rejection.MALFORMED)
        val right = bytes.u24(payload) * 2L + bytes.u24(payload + 6) + 1
        val bottom = bytes.u24(payload + 3) * 2L + bytes.u24(payload + 9) + 1
        if (right > width || bottom > height) MediaScanner.reject(Rejection.MALFORMED)
        if (++frames > MediaScanner.MAX_FRAMES) MediaScanner.reject(Rejection.TOO_MANY_FRAMES)
    }

    private companion object {
        const val RIFF_SIZE_AT = 4
        const val RIFF_HEADER = 8L
        const val FIRST_CHUNK = 12
        const val FOURCC = 4
        const val CHUNK_HEADER = 8
        const val VP8X_SIZE = 10
        const val CANVAS_AT = 4
        const val ANIMATION_FLAG = 0x02
        const val LOSSY_SIZE = 10
        const val START_CODE = 0x2A019D
        const val LOSSLESS_SIZE = 5
        const val LOSSLESS_SIGNATURE = 0x2F
        const val DIMENSION_MASK = 0x3FFF
        const val DIMENSION_BITS = 14
        const val FRAME_HEADER = 16
    }
}
