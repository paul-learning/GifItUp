package app.gifitup.server.auth

/**
 * Who may be called what (ADR-0007, #12). The rule is the crate's `is_valid_username`, byte for byte: both sides test
 * the same cases (`username-rule-cases.txt`). The server also reserves names that could be mistaken for staff or the
 * system; the crate doesn't need to know them (a client simply can't register one).
 */
object UsernamePolicy {
    private val RULE = Regex("^[a-z0-9_]{3,32}$")

    /** Checked on the raw input: no trimming, no case folding, no Unicode normalization. */
    fun isValid(username: String): Boolean = RULE.matches(username)

    fun isReserved(username: String): Boolean = username in RESERVED

    private val RESERVED =
        setOf(
            "admin",
            "administrator",
            "root",
            "system",
            "gifitup",
            "support",
            "help",
            "security",
            "moderator",
            "mod",
            "staff",
            "official",
            "owner",
            "server",
            "null",
            "undefined",
            "unknown",
            "anonymous",
            "everyone",
            "all",
            "me",
            "you",
        )
}
