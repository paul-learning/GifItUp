package app.gifitup

import app.gifitup.shared.data.crypto.MlsClientFactory
import app.gifitup.shared.domain.auth.DeviceStorage

/**
 * What each platform's composition root (ADR-0004) hands to the shared UI. The MLS factory is loaded lazily:
 * the web module downloads and instantiates the wasm file only when first needed (ADR-0007).
 *
 * [deviceStorage] is built once per app instance, so every use case built from it shares one `DeviceLock` (ADR-0011,
 * #108 SEC-22). Both platforms build it in their composition root: the web in `main()`, Android in `GifItUpApp`.
 */
class AppGraph(
    val mlsClientFactory: suspend () -> MlsClientFactory,
    val deviceStorage: DeviceStorage?,
)
