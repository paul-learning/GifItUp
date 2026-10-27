package app.gifitup.shared.domain.media

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** The scanner on hand-built files: valid ones, every limit, truncation at every length, and random damage. */
class MediaScannerTest {
    private fun le16(value: Int) = byteArrayOf(value.toByte(), (value shr 8).toByte())

    private fun le24(value: Int) = byteArrayOf(value.toByte(), (value shr 8).toByte(), (value shr 16).toByte())

    private fun le32(value: Long) = byteArrayOf(value.toByte(), (value shr 8).toByte(), (value shr 16).toByte(), (value shr 24).toByte())

    private fun ascii(text: String) = text.encodeToByteArray()

    /** A GIF with [frames] one-block images and a 2-colour global table. */
    private fun gif(
        width: Int = 16,
        height: Int = 8,
        frames: Int = 1,
        trailer: Boolean = true,
        extensions: Int = 0,
    ): ByteArray {
        var out = ascii("GIF89a") + le16(width) + le16(height) + byteArrayOf(0x80.toByte(), 0, 0) + ByteArray(6)
        repeat(extensions) { out += byteArrayOf(0x21, 0xF9.toByte(), 4, 0, 0, 0, 0, 0) }
        repeat(frames) {
            out += byteArrayOf(0x2C) + le16(0) + le16(0) + le16(width) + le16(height) + byteArrayOf(0)
            out += byteArrayOf(2, 3, 1, 2, 3, 0)
        }
        return if (trailer) out + byteArrayOf(0x3B) else out
    }

    private fun riff(vararg chunks: ByteArray): ByteArray {
        val body = ascii("WEBP") + chunks.fold(ByteArray(0)) { acc, chunk -> acc + chunk }
        return ascii("RIFF") + le32(body.size.toLong()) + body
    }

    private fun chunk(
        type: String,
        payload: ByteArray,
    ) = ascii(type) + le32(payload.size.toLong()) + payload + (if (payload.size % 2 == 1) byteArrayOf(0) else ByteArray(0))

    private fun vp8x(
        width: Int,
        height: Int,
        animated: Boolean,
    ) = chunk("VP8X", byteArrayOf(if (animated) 2 else 0, 0, 0, 0) + le24(width - 1) + le24(height - 1))

    private fun anmf(
        x: Int = 0,
        y: Int = 0,
        width: Int,
        height: Int,
    ) = chunk(
        "ANMF",
        le24(x / 2) + le24(y / 2) + le24(width - 1) + le24(height - 1) + le24(100) + byteArrayOf(0) +
            chunk("VP8L", losslessPayload(width, height)),
    )

    private fun losslessPayload(
        width: Int,
        height: Int,
    ) = byteArrayOf(0x2F) + le32((width - 1).toLong() or ((height - 1).toLong() shl 14)) + byteArrayOf(0, 0)

    private fun lossy(
        width: Int,
        height: Int,
    ) = chunk("VP8 ", byteArrayOf(0, 0, 0, 0x9D.toByte(), 0x01, 0x2A) + le16(width) + le16(height) + byteArrayOf(0, 0))

    private fun accepted(data: ByteArray): MediaInfo = assertIs<ScanResult.Accepted>(MediaScanner.scan(data)).info

    private fun rejected(data: ByteArray): Rejection = assertIs<ScanResult.Rejected>(MediaScanner.scan(data)).reason

    private val samples
        get() =
            listOf(
                gif(),
                gif(frames = 5, extensions = 3),
                riff(lossy(32, 16)),
                riff(chunk("VP8L", losslessPayload(10, 12))),
                riff(
                    vp8x(64, 48, animated = true),
                    chunk("ANIM", ByteArray(6)),
                    anmf(width = 64, height = 48),
                    anmf(x = 8, y = 4, width = 20, height = 20),
                ),
                riff(vp8x(40, 30, animated = false), chunk("VP8L", losslessPayload(40, 30)), chunk("EXIF", ByteArray(7))),
            )

    @Test
    fun validFilesGiveTheirRealSizeAndFrameCount() {
        accepted(gif()).let { assertEquals(listOf(MediaFormat.GIF, 16, 8, 1), listOf(it.format, it.width, it.height, it.frames)) }
        assertEquals(5, accepted(gif(frames = 5, extensions = 3)).frames)
        accepted(
            riff(lossy(32, 16)),
        ).let { assertEquals(listOf(MediaFormat.WEBP, 32, 16, 1), listOf(it.format, it.width, it.height, it.frames)) }
        accepted(
            riff(chunk("VP8L", losslessPayload(10, 12))),
        ).let { assertEquals(listOf(10, 12, 1), listOf(it.width, it.height, it.frames)) }
        accepted(samples[4]).let { assertEquals(listOf(64, 48, 2), listOf(it.width, it.height, it.frames)) }
        accepted(samples[5]).let { assertEquals(listOf(40, 30, 1), listOf(it.width, it.height, it.frames)) }
    }

    @Test
    fun theLimitsAreEnforcedAtTheirEdges() {
        assertEquals(2048, accepted(gif(width = 2048, height = 1)).width)
        assertEquals(Rejection.CANVAS_TOO_LARGE, rejected(gif(width = 2049, height = 1)))
        assertEquals(Rejection.CANVAS_TOO_LARGE, rejected(riff(vp8x(2049, 10, animated = false), chunk("VP8L", losslessPayload(10, 10)))))
        assertEquals(1000, accepted(gif(width = 2, height = 2, frames = 1000)).frames)
        assertEquals(Rejection.TOO_MANY_FRAMES, rejected(gif(width = 2, height = 2, frames = 1001)))
        val animated = (1..1001).map { anmf(width = 2, height = 2) }.toTypedArray()
        assertEquals(Rejection.TOO_MANY_FRAMES, rejected(riff(vp8x(2, 2, animated = true), *animated)))
        // 2048 x 2048 x 4 bytes x 8 frames is 128 MiB: the limit; 9 frames is over it.
        assertEquals(8, accepted(gif(width = 2048, height = 2048, frames = 8)).frames)
        assertEquals(Rejection.TOO_MUCH_MEMORY, rejected(gif(width = 2048, height = 2048, frames = 9)))
        assertEquals(Rejection.MALFORMED, rejected(gif(width = 0, height = 5)))
    }

    @Test
    fun theSenderSHintsPlayNoPart() {
        // The scanner takes bytes only; a 16 x 8 file is 16 x 8 whatever a message claimed.
        assertEquals(16, accepted(gif()).width)
    }

    @Test
    fun unknownAndMalformedFilesAreRejected() {
        assertEquals(Rejection.UNKNOWN_FORMAT, rejected(ByteArray(0)))
        assertEquals(Rejection.UNKNOWN_FORMAT, rejected(ascii("<html>not an image</html>")))
        assertEquals(Rejection.UNKNOWN_FORMAT, rejected(byteArrayOf(0x89.toByte()) + ascii("PNG\r\n") + ByteArray(40)))
        assertEquals(Rejection.UNKNOWN_FORMAT, rejected(ascii("RIFF") + le32(4) + ascii("WAVE")))
        assertEquals(Rejection.MALFORMED, rejected(gif(frames = 0)))
        assertEquals(Rejection.MALFORMED, rejected(gif(trailer = false)))
        assertEquals(Rejection.MALFORMED, rejected(gif(trailer = false) + byteArrayOf(0x77)), "a block id that does not exist")
        assertEquals(Rejection.MALFORMED, rejected(riff(chunk("VP8L", losslessPayload(4, 4))).copyOf(20)))
        assertEquals(Rejection.MALFORMED, rejected(riff(chunk("JUNK", ByteArray(10)))), "the first chunk must be an image or VP8X")
        assertEquals(Rejection.MALFORMED, rejected(riff(vp8x(4, 4, animated = true))), "animated without frames")
        assertEquals(Rejection.MALFORMED, rejected(riff(vp8x(4, 4, animated = false))), "no image")
        assertEquals(
            Rejection.MALFORMED,
            rejected(riff(vp8x(4, 4, animated = false), anmf(width = 4, height = 4))),
            "a frame without the flag",
        )
        assertEquals(
            Rejection.MALFORMED,
            rejected(riff(vp8x(8, 8, animated = true), anmf(x = 6, width = 4, height = 4))),
            "frame outside the canvas",
        )
    }

    @Test
    fun aChunkSizeThatPointsPastTheFileIsMalformed() {
        val huge = ascii("RIFF") + le32(0x7FFFFFF0) + ascii("WEBP") + ascii("VP8 ") + le32(0xFFFFFFFFL) + ByteArray(20)
        assertEquals(Rejection.MALFORMED, rejected(huge))
        val bigChunk = ascii("RIFF") + le32(30) + ascii("WEBP") + ascii("VP8L") + le32(0xFFFFFFF0L) + ByteArray(10)
        assertEquals(Rejection.MALFORMED, rejected(bigChunk))
    }

    @Test
    fun aFileOverTheSizeCapIsRejectedWithoutBeingWalked() {
        assertIs<ScanResult.Rejected>(MediaScanner.scan(gif() + ByteArray(MediaScanner.MAX_BYTES)))
    }

    @Test
    fun aCraftedFileWithManyTinyBlocksStopsAtTheStepLimit() {
        val blocks = ByteArray(2 * (MediaScanner.MAX_STEPS + 10)) { if (it % 2 == 0) 1 else 0x55 }
        val base = ascii("GIF89a") + le16(4) + le16(4) + byteArrayOf(0, 0, 0)
        val tooComplex = base + byteArrayOf(0x21, 0xFF.toByte()) + blocks + byteArrayOf(0, 0x3B)
        assertEquals(Rejection.TOO_COMPLEX, rejected(tooComplex))
        val junk = ascii("JUNK") + le32(0)
        val body = ascii("WEBP") + chunk("VP8L", losslessPayload(2, 2))
        val chunks = ByteArray(8 * (MediaScanner.MAX_STEPS + 10))
        for (n in 0 until MediaScanner.MAX_STEPS + 10) junk.copyInto(chunks, n * 8)
        val webp = ascii("RIFF") + le32((body.size + chunks.size).toLong()) + body + chunks
        assertEquals(Rejection.TOO_COMPLEX, rejected(webp))
    }

    @Test
    fun everyTruncationOfAValidFileIsRejectedAndNeverThrows() {
        for (sample in samples) {
            for (length in 0 until sample.size) {
                assertIs<ScanResult.Rejected>(MediaScanner.scan(sample.copyOf(length)), "length $length of ${sample.size}")
            }
        }
    }

    @Test
    fun randomDamageNeverThrowsAndAcceptedFilesStayInsideTheLimits() {
        val random = Random(20261027)
        repeat(30_000) {
            val sample = samples[random.nextInt(samples.size)].copyOf()
            repeat(1 + random.nextInt(4)) {
                when (random.nextInt(3)) {
                    0 -> {
                        sample[random.nextInt(sample.size)] = random.nextInt(256).toByte()
                    }

                    1 -> {
                        sample[random.nextInt(sample.size)] =
                            (sample[random.nextInt(sample.size)].toInt() xor (1 shl random.nextInt(8))).toByte()
                    }

                    else -> {
                        sample[random.nextInt(sample.size)] = if (random.nextBoolean()) 0xFF.toByte() else 0
                    }
                }
            }
            val damaged = if (random.nextInt(4) == 0) sample.copyOf(random.nextInt(sample.size + 1)) else sample
            val result = MediaScanner.scan(damaged)
            if (result is ScanResult.Accepted) {
                val info = result.info
                assertEquals(true, info.width in 1..MediaScanner.MAX_SIDE && info.height in 1..MediaScanner.MAX_SIDE)
                assertEquals(true, info.frames in 1..MediaScanner.MAX_FRAMES)
            }
        }
    }

    @Test
    fun randomBytesNeverThrow() {
        val random = Random(7)
        repeat(5_000) {
            val data = random.nextBytes(random.nextInt(200))
            if (data.size > 12 && random.nextBoolean()) {
                val header = if (random.nextBoolean()) ascii("GIF89a") else ascii("RIFF") + le32(random.nextLong(0, 300)) + ascii("WEBP")
                header.copyInto(data)
            }
            MediaScanner.scan(data)
        }
    }
}
