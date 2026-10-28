package app.gifitup.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.gifitup.shared.domain.media.MediaInfo

/**
 * Draws the bytes of a GIF or animated WebP, animated, scaled to fit [modifier]'s box. The bytes passed the media
 * scanner (ADR-0003) and are given to the platform's own decoder: Android's `ImageDecoder`, the browser's codecs on the
 * web (never Skiko's bundled ones, GIF spike).
 * [onFailed] is called once if the decoder can't show them; then nothing is drawn.
 */
@Composable
internal expect fun AnimatedGif(
    data: ByteArray,
    info: MediaInfo,
    contentDescription: String,
    modifier: Modifier,
    onFailed: () -> Unit,
)
