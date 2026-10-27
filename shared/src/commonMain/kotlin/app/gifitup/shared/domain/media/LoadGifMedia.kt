package app.gifitup.shared.domain.media

import app.gifitup.shared.data.content.GifReference

/** What the chat shows for a GIF: the checked bytes, or a placeholder. Nothing here carries text from the file. */
sealed interface MediaLoad {
    /** [data] passed the scanner; the platform decoder may now be given it. [info] is what the file itself says. */
    class Ready(
        val data: ByteArray,
        val info: MediaInfo,
    ) : MediaLoad

    /** The GIF can't be shown; the message stays as a placeholder tile. */
    class Unavailable(
        val reason: UnavailableReason,
    ) : MediaLoad
}

enum class UnavailableReason { FETCH_FAILED, TOO_LARGE, REJECTED }

/**
 * Fetches the media of a GIF message and scans it before anything decodes it (ADR-0003). The bytes are returned for
 * the platform decoder and not stored: chat history keeps the reference, not the media.
 */
class LoadGifMedia(
    private val fetcher: MediaFetcher,
) {
    suspend operator fun invoke(reference: GifReference): MediaLoad =
        when (val fetched = fetcher.fetch(reference)) {
            FetchResult.Failed -> {
                MediaLoad.Unavailable(UnavailableReason.FETCH_FAILED)
            }

            FetchResult.TooLarge -> {
                MediaLoad.Unavailable(UnavailableReason.TOO_LARGE)
            }

            is FetchResult.Bytes -> {
                when (val scanned = MediaScanner.scan(fetched.data)) {
                    // Only the scanned part is handed on: bytes after the trailer or the RIFF end are not part of the file.
                    is ScanResult.Accepted -> MediaLoad.Ready(fetched.data.copyOf(scanned.info.length), scanned.info)

                    is ScanResult.Rejected -> MediaLoad.Unavailable(UnavailableReason.REJECTED)
                }
            }
        }
}
