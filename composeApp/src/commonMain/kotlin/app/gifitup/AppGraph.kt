package app.gifitup

import app.gifitup.shared.data.crypto.MlsClientFactory

/**
 * What each platform's composition root (ADR-0004) hands to the shared UI. The MLS factory is loaded lazily:
 * the web module downloads and instantiates the wasm file only when first needed (ADR-0007).
 */
class AppGraph(
    val mlsClientFactory: suspend () -> MlsClientFactory,
)
