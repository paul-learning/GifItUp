package app.gifitup.server.auth

import java.util.UUID

/** What an auth signature authorizes (ADR-0009). A closed set; [wire] is the byte in the signed content. */
enum class AuthPurpose(
    val wire: Int,
) {
    REGISTER(1),
    LOGIN(2),
}

/**
 * Who a signature is about (#71 SEC-31). The type fixes the purpose, so a register signature can only ever be checked
 * against a username and a login signature only against a device id, and each subject has exactly one spelling.
 */
sealed interface AuthSubject {
    val purpose: AuthPurpose

    /** The text that is signed. */
    val text: String

    /** Registration: a username that passed [UsernamePolicy]. */
    class Username private constructor(
        override val text: String,
    ) : AuthSubject {
        override val purpose get() = AuthPurpose.REGISTER

        companion object {
            fun of(username: String): Username? = if (UsernamePolicy.isValid(username)) Username(username) else null
        }
    }

    /**
     * Login: a device id. Always written in the canonical lowercase form (`UUID.toString()`), never as the client spelled
     * it: `UUID.fromString` accepts other spellings that would sign different bytes.
     */
    class Device(
        id: UUID,
    ) : AuthSubject {
        override val purpose get() = AuthPurpose.LOGIN
        override val text: String = id.toString()
    }
}

/**
 * The server's public origin in the one spelling the crate signs (`is_canonical_origin` in crypto/src/auth.rs, ADR-0009):
 * `https://` + lowercase host (letters, digits, `-`, `.`; no empty labels, no leading or trailing `-`) + optional port
 * 1..65535 without leading zeros and never 443, nothing after it, at most 256 bytes. Comes from the server's own
 * configuration, never from a request (#70 SEC-25).
 */
@JvmInline
value class CanonicalOrigin private constructor(
    val text: String,
) {
    companion object {
        private const val MAX_LENGTH = 256
        private const val MAX_PORT = 65_535
        private const val DEFAULT_HTTPS_PORT = 443
        private val LABEL = Regex("^[a-z0-9]([a-z0-9-]*[a-z0-9])?$")
        private val PORT = Regex("^[1-9][0-9]{0,4}$")

        fun of(origin: String): CanonicalOrigin? = if (isCanonical(origin)) CanonicalOrigin(origin) else null

        fun isCanonical(origin: String): Boolean {
            val authority = origin.removePrefix("https://")
            if (origin.length > MAX_LENGTH || authority == origin) return false
            val host = authority.substringBefore(':')
            val port = authority.substringAfter(':', "")
            val hostOk = host.isNotEmpty() && host.split('.').all { LABEL.matches(it) }
            val portOk =
                !authority.contains(':') ||
                    (PORT.matches(port) && port.toInt().let { it <= MAX_PORT && it != DEFAULT_HTTPS_PORT })
            return hostOk && portOk
        }
    }
}
