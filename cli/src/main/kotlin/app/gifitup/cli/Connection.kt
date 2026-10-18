package app.gifitup.cli

import app.gifitup.shared.data.network.ApiEndpoint
import app.gifitup.shared.data.network.DevOnlyEndpoint
import java.net.URI
import java.net.URISyntaxException

/**
 * Turns `--server` (and `--origin`) into the one endpoint the CLI talks to (#79 plan SEC-1, SEC-7):
 * - `https://host[:port]`: the origin is derived from the server and must already be canonical; a different `--origin`
 *   is refused, so a signature for one origin never goes to another host;
 * - `http://127.0.0.1:<port>` or `http://[::1]:<port>`: only these literals (never resolved, no `localhost`), and then
 *   `--origin` names the origin the local server is configured with (the end-to-end test's `https://e2e.invalid`).
 *
 * No userinfo, path, query or fragment, and no option to skip TLS checks.
 */
object Connection {
    fun endpoint(
        server: String,
        origin: String?,
    ): ApiEndpoint {
        val uri =
            try {
                URI(server)
            } catch (ignored: URISyntaxException) {
                refuse("--server is not a valid URL")
            }
        if (!hasOnlySchemeHostPort(uri)) {
            refuse("--server takes only scheme, host and port: no user, path, query or fragment")
        }
        return when (uri.scheme) {
            "https" -> https(uri, server, origin)
            "http" -> loopback(uri, origin)
            else -> refuse("--server must be https://, or http:// to 127.0.0.1 or [::1]")
        }
    }

    private fun https(
        uri: URI,
        server: String,
        origin: String?,
    ): ApiEndpoint {
        val endpoint =
            ApiEndpoint.forOrigin(server)
                ?: refuse("--server must be a canonical https origin (lowercase host, no :443, nothing after it)")
        if (origin != null && origin != endpoint.origin) {
            refuse("--origin can't differ from an https --server (it is ${uri.host})")
        }
        return endpoint
    }

    @OptIn(DevOnlyEndpoint::class)
    private fun loopback(
        uri: URI,
        origin: String?,
    ): ApiEndpoint {
        val ipv6 =
            when (uri.host) {
                "127.0.0.1" -> false
                "[::1]" -> true
                else -> refuse("plain http only to the literal 127.0.0.1 or [::1]")
            }
        if (uri.port == -1) refuse("an http --server needs an explicit port")
        origin ?: refuse("an http --server needs --origin, the https origin that server is configured with")
        return ApiEndpoint.loopback(origin, uri.port, ipv6)
            ?: refuse("--origin must be a canonical https origin")
    }

    private fun hasOnlySchemeHostPort(uri: URI): Boolean =
        !uri.isOpaque && uri.rawUserInfo == null && uri.rawPath.isNullOrEmpty() && uri.rawQuery == null && uri.rawFragment == null

    private fun refuse(message: String): Nothing = throw CliError(message, EXIT_USAGE)
}
