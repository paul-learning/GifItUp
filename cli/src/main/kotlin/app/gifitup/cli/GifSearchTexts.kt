package app.gifitup.cli

import app.gifitup.shared.domain.gif.GifFailure
import app.gifitup.shared.domain.gif.GifResult
import app.gifitup.shared.domain.gif.GifTile
import java.io.PrintStream

/**
 * What `gif-search` prints: one line per tile with the slug and the two public CDN links (they passed the app's strict URL
 * rule, so they hold no control character), `placeholder` for a result that failed it, and a fixed line for a failure. Never
 * the key, a title or any other text of the answer.
 */
internal fun printGifResult(
    result: GifResult,
    out: PrintStream,
): Int =
    when (result) {
        is GifResult.Page -> {
            result.tiles.forEach { out.println(tileLine(it)) }
            val placeholders = result.tiles.count { it === GifTile.Placeholder }
            out.println("${result.tiles.size} tiles, $placeholders placeholders, next page: ${if (result.hasNext) "yes" else "no"}")
            EXIT_OK
        }

        is GifResult.Failed -> {
            throw CliError(failureText(result.reason, result.retryAfterSeconds), EXIT_SERVER)
        }
    }

private fun tileLine(tile: GifTile): String =
    when (tile) {
        is GifTile.Item -> "gif ${tile.send.slug} preview ${tile.preview.url} send ${tile.send.url}"
        GifTile.Placeholder -> "placeholder"
    }

private const val DEFAULT_WAIT_SECONDS = 60

internal fun failureText(
    reason: GifFailure,
    retryAfter: Int?,
): String =
    when (reason) {
        GifFailure.UNAVAILABLE -> "the GIF provider is temporarily unavailable (no key, or it refused the key)"
        GifFailure.RATE_LIMITED -> "the GIF provider's limit is reached; try again in ${retryAfter ?: DEFAULT_WAIT_SECONDS} seconds"
        GifFailure.OFFLINE -> "the GIF provider could not be reached"
        GifFailure.MALFORMED -> "the GIF provider's answer was not what the app expects"
    }
