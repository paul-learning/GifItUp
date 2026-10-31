package app.gifitup

import app.gifitup.shared.data.crypto.HistoryCipherFactory
import app.gifitup.shared.data.crypto.MlsClientFactory
import app.gifitup.shared.data.crypto.SecureRandomSource
import app.gifitup.shared.domain.auth.DeviceStorage
import app.gifitup.shared.domain.media.DeviceSecurityPatch
import app.gifitup.shared.domain.media.MediaFetcher
import app.gifitup.shared.domain.messages.GraphemeCounter
import app.gifitup.shared.domain.messages.HistoryLimits
import app.gifitup.shared.presentation.invite.SensitiveClipboard
import io.ktor.client.engine.HttpClientEngine

/**
 * What each platform's composition root (ADR-0004) hands to the shared UI. The MLS factory is loaded lazily:
 * the web module downloads and instantiates the wasm file only when first needed (ADR-0007).
 *
 * [deviceStorage] is built once per app instance, so every use case built from it shares one `DeviceLock` (ADR-0011,
 * #108 SEC-22). Both platforms build it in their composition root: the web in `main()`, Android in `GifItUpApp`.
 * [engine] makes the platform's HTTP engine (a new one per server origin; the UI closes it), [random] is the platform
 * CSPRNG for the snapshot key. [clipboard] is where an invite link the user copies goes (the platform marks it
 * sensitive where it can); it must not hold an Activity, since the ViewModels that use it outlive one. [glyphs] tells the
 * Friends screen whether the device draws an emoji (ADR-0014). [conversations] is what the conversation screen (#25)
 * needs from the platform. [historyCiphers] is the platform's message-history cipher (the web loads its wasm module on the
 * first call, like [mlsClientFactory]) and [historyLimits] how much history it keeps (Android 1000 messages per group, the
 * web 200; ADR-0017).
 */
@Suppress("LongParameterList") // One record of what a platform hands over; grouping the fields would only rename them.
class AppGraph(
    val mlsClientFactory: suspend () -> MlsClientFactory,
    val deviceStorage: DeviceStorage?,
    val engine: () -> HttpClientEngine,
    val random: SecureRandomSource,
    val clipboard: SensitiveClipboard,
    val glyphs: GlyphSupport,
    val conversations: ConversationPlatform,
    val historyCiphers: suspend () -> HistoryCipherFactory,
    val historyLimits: HistoryLimits,
)

/**
 * What the conversation screen needs from the platform (#25): [mediaFetcher] makes a fetcher per conversation flow (one
 * that is `AutoCloseable` is closed with it), [securityPatch] is `null` where the browser updates its own codecs
 * (ADR-0003), [graphemes] counts the characters of a caption.
 */
class ConversationPlatform(
    val mediaFetcher: () -> MediaFetcher,
    val securityPatch: DeviceSecurityPatch?,
    val graphemes: GraphemeCounter,
)
