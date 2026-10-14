package app.gifitup.protocol

import kotlinx.serialization.json.Json

/**
 * The one JSON configuration for all wire models. Clients and server must both use it.
 *
 * `ignoreUnknownKeys` keeps older clients working when the server adds fields (backward compatibility rule).
 */
val ProtocolJson: Json =
    Json {
        ignoreUnknownKeys = true
    }
