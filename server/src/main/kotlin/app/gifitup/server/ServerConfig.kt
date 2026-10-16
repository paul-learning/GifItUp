package app.gifitup.server

/**
 * Typed server configuration (ARCH-M0-10). Built once from the environment in `main()` and passed to
 * [module][app.gifitup.server.module]; nothing else reads environment variables.
 */
data class ServerConfig(
    val port: Int = DEFAULT_PORT,
    val limits: HttpLimits = HttpLimits(),
) {
    init {
        require(port in 1..MAX_PORT) { "port out of range" }
    }

    companion object {
        const val DEFAULT_PORT = 8080
        private const val MAX_PORT = 65_535

        /**
         * Reads the configuration from [env]. Invalid values fail startup instead of falling back to a default, so a
         * typo in the deployment can't silently change behaviour. Error messages name the variable, never its value.
         */
        fun fromEnv(env: Map<String, String>): ServerConfig {
            val port =
                env["PORT"]?.let { raw ->
                    require(raw.matches(PORT_PATTERN)) { "PORT must be a decimal port number" }
                    raw.toInt()
                } ?: DEFAULT_PORT
            return ServerConfig(port = port)
        }

        private val PORT_PATTERN = Regex("^[1-9][0-9]{0,4}$")
    }
}

/**
 * Limits the server enforces itself. Peers on the shared `proxy` network reach the server without going through Caddy
 * (ADR-0005, #46 SEC-1), so Caddy's limits are not authoritative. See #12 round 0, SEC-8.
 */
data class HttpLimits(
    /** Request line (method, path, version). Netty's decoder answers 400 and closes above it. */
    val maxInitialLineLength: Int = 2_048,
    /** All request headers together. Netty's decoder answers 400 and closes above it. */
    val maxHeaderSize: Int = 8_192,
    val maxChunkSize: Int = 8_192,
    /**
     * Idle read timeout (Netty `ReadTimeoutHandler`): a connection that sends nothing for this long is closed, idle
     * keep-alive connections included. It is not a deadline for the whole request, so a peer trickling bytes stays
     * connected; the container's memory and PID limits bound that.
     */
    val requestReadTimeoutSeconds: Int = 10,
    val responseWriteTimeoutSeconds: Int = 10,
    /** Body limit for every route that doesn't set its own, enforced before deserialization (ADR-0006 A9). */
    val defaultBodyLimitBytes: Long = DEFAULT_BODY_LIMIT_BYTES,
) {
    init {
        require(maxInitialLineLength > 0 && maxHeaderSize > 0 && maxChunkSize > 0) { "sizes must be positive" }
        require(requestReadTimeoutSeconds > 0 && responseWriteTimeoutSeconds > 0) { "timeouts must be positive" }
        require(defaultBodyLimitBytes > 0) { "body limit must be positive" }
    }

    private companion object {
        /** 16 KiB: enough for every auth request (#12); routes with bigger bodies set their own limit. */
        const val DEFAULT_BODY_LIMIT_BYTES = 16_384L
    }
}
