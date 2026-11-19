package app.gifitup.shared.presentation.session

import app.gifitup.shared.data.crypto.HistoryCipherFactory
import app.gifitup.shared.data.crypto.SecureRandomSource
import app.gifitup.shared.data.gif.ProviderFetcher
import app.gifitup.shared.domain.diagnostics.DiagnosticsLog
import app.gifitup.shared.domain.media.MediaFetcher
import app.gifitup.shared.domain.messages.GraphemeCounter
import app.gifitup.shared.domain.messages.HistoryLimits
import app.gifitup.shared.presentation.invite.SensitiveClipboard
import io.ktor.client.engine.HttpClientEngine

/**
 * What the platform's composition root hands to one [OriginSession] (ADR-0004, ADR-0013): the ports that differ between
 * Android and the web. The platform builds it once; the session makes what it needs from it per origin.
 *
 * [engine] makes the HTTP engine of the API (a new one per origin: the session closes it), [socketEngine] the engine of
 * the push channel's socket only (ADR-0020), [mediaFetcher] and [providerFetcher] make a fetcher per session (one that is
 * `AutoCloseable` is closed with it), [graphemes] counts the characters of a caption. [diagnostics] is the one log of the
 * app process, [platform] the report's platform line and [version] and [defaultOrigin] the build's own values.
 */
@Suppress("LongParameterList") // One record of what a platform hands over; grouping the fields would only rename them.
class OriginDeps(
    val engine: () -> HttpClientEngine,
    val socketEngine: () -> HttpClientEngine,
    val random: SecureRandomSource,
    val clipboard: SensitiveClipboard,
    val mediaFetcher: () -> MediaFetcher,
    val providerFetcher: () -> ProviderFetcher,
    val graphemes: GraphemeCounter,
    val historyCiphers: suspend () -> HistoryCipherFactory,
    val historyLimits: HistoryLimits,
    val diagnostics: DiagnosticsLog,
    val platform: String,
    val version: String,
    val defaultOrigin: String,
)
