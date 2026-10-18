package app.gifitup.shared.domain.auth

/**
 * Keeps the dev CLI away from real accounts (#79 plan, round-0 SEC-2; the owner's decision on #79). Keyed on the
 * **origin** the signatures name, never on where requests go, so a tunnel to the real server from a loopback port
 * doesn't skip it. A test origin (`*.invalid`, `*.test`, `localhost`) is always allowed; any other origin needs the
 * caller's explicit remote-test-account flag and a username starting with [PREFIX].
 *
 * This is a guard against accidents, not against an adversary: the CLI is a dev tool and anyone can build one without
 * it. The app never calls it; the CLI must, before every command that talks to a server (#94 SEC-14 delta).
 */
object TestAccountGuard {
    const val PREFIX = "cli_"

    enum class Refusal {
        /** A non-test origin without the remote-test-account flag. */
        NOT_A_TEST_ORIGIN,

        /** A non-test origin with the flag, but the username doesn't start with [PREFIX]. */
        NOT_A_TEST_USERNAME,
    }

    /** Null if [username] may be used against [origin]; otherwise why not. */
    fun check(
        origin: String,
        username: String,
        remoteTestAccount: Boolean,
    ): Refusal? =
        when {
            isTestOrigin(origin) -> null
            !remoteTestAccount -> Refusal.NOT_A_TEST_ORIGIN
            !username.startsWith(PREFIX) -> Refusal.NOT_A_TEST_USERNAME
            else -> null
        }

    /** `https://` origins whose host is `localhost` or ends in the reserved `.invalid` / `.test` TLDs (RFC 6761). */
    fun isTestOrigin(origin: String): Boolean {
        val host = origin.removePrefix("https://").substringBefore(':')
        return origin.startsWith("https://") && (host == "localhost" || host.endsWith(".invalid") || host.endsWith(".test"))
    }
}
