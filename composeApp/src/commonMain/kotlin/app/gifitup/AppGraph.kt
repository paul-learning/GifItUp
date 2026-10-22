package app.gifitup

import app.gifitup.shared.data.crypto.MlsClientFactory
import app.gifitup.shared.data.crypto.SecureRandomSource
import app.gifitup.shared.domain.auth.DeviceStorage
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
 * sensitive where it can); it must not hold an Activity, since the ViewModels that use it outlive one.
 */
class AppGraph(
    val mlsClientFactory: suspend () -> MlsClientFactory,
    val deviceStorage: DeviceStorage?,
    val engine: () -> HttpClientEngine,
    val random: SecureRandomSource,
    val clipboard: SensitiveClipboard,
)
