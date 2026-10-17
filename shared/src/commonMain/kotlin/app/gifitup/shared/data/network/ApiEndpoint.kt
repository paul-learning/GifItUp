package app.gifitup.shared.data.network

/**
 * Where the client sends its requests, and the origin its auth signatures are bound to (ADR-0009).
 *
 * The app uses [forOrigin] only: the base URL **is** the configured origin, so what gets signed and where it goes can
 * never differ (#79 SEC-1). [loopback] is the one exception, for the dev CLI and the end-to-end test against a local
 * server: plain http to a literal loopback address that is built here, never parsed from input (#79 SEC-7).
 */
class ApiEndpoint private constructor(
    /** The origin auth signatures name, in canonical form. */
    val origin: String,
    internal val baseUrl: String,
) {
    override fun toString(): String = "ApiEndpoint(origin=$origin, baseUrl=$baseUrl)"

    companion object {
        /** The endpoint for a configured [origin], or null if it isn't canonical. */
        fun forOrigin(origin: String): ApiEndpoint? = origin.takeIf(::isCanonicalOrigin)?.let { ApiEndpoint(it, it) }

        /**
         * Plain http to `127.0.0.1` (or `[::1]` with [ipv6]) on [port], signing [origin]. Dev CLI and tests only: the
         * app never calls this. Null if [origin] isn't canonical or [port] is out of range.
         */
        fun loopback(
            origin: String,
            port: Int,
            ipv6: Boolean = false,
        ): ApiEndpoint? {
            if (!isCanonicalOrigin(origin) || port !in 1..MAX_PORT) return null
            val host = if (ipv6) "[::1]" else "127.0.0.1"
            return ApiEndpoint(origin, "http://$host:$port")
        }

        /**
         * The crate's canonical-origin rule (`crypto/src/auth.rs`, ADR-0009): `https://`, a lowercase host without
         * empty labels or leading/trailing `-`, an optional port 1..65535 without leading zeros and never 443, nothing
         * after it, at most 256 characters.
         */
        fun isCanonicalOrigin(origin: String): Boolean {
            if (origin.length > MAX_ORIGIN || !ORIGIN.matches(origin)) return false
            val port = origin.removePrefix(HTTPS).substringAfter(':', "").toIntOrNull()
            return port == null || (port <= MAX_PORT && port != DEFAULT_HTTPS_PORT)
        }

        private const val HTTPS = "https://"
        private const val MAX_ORIGIN = 256
        private const val MAX_PORT = 65_535
        private const val DEFAULT_HTTPS_PORT = 443
        private val ORIGIN =
            Regex("""^https://[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)*(?::[1-9][0-9]{0,4})?$""")
    }
}
