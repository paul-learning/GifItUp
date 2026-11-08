package app.gifitup.android

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp

/**
 * The one place the OkHttp engine is created (#27, ADR-0015 and its engine ADR): the push channel's WebSocket only. REST,
 * GIF search and media stay on the Android engine until the "one engine" follow-up (#272). TLS is the platform's: no
 * `sslSocketFactory`, trust manager, hostname verifier or pinner is set here or anywhere else (`scripts/check-repo-hygiene.sh`
 * enforces it), so the system trust store and the network security config (no cleartext, no user CAs) decide, as for REST.
 * Redirects are refused at both layers.
 */
object SocketEngine {
    fun create(): HttpClientEngine =
        OkHttp.create {
            config {
                followRedirects(false)
                followSslRedirects(false)
            }
        }
}
