package app.gifitup.shared.domain.invite

import app.gifitup.shared.data.network.ApiEndpoint
import app.gifitup.shared.domain.auth.InputRules

/**
 * An invite code (ADR-0010): 22 characters of canonical base64url. It is a one-use credential, so it never prints:
 * [toString] is fixed text, and the only way to the text is [reveal], used by the register request and nothing else.
 * Never a route, a route argument, saved UI state or a log line (#17 plan review, SEC-3).
 */
class InviteCode private constructor(
    private val value: String,
) {
    fun reveal(): String = value

    override fun toString(): String = "InviteCode(…)"

    override fun equals(other: Any?): Boolean = other is InviteCode && value == other.value

    override fun hashCode(): Int = value.hashCode()

    companion object {
        /** The code if [text] is exactly a canonical code, else null. */
        fun of(text: String): InviteCode? = text.takeIf(InputRules::isValidInviteCode)?.let(::InviteCode)
    }
}

/** What a pasted string turned out to be. */
sealed interface InviteInput {
    /** A bare code: no origin to compare, so the UI shows the origin it would be sent to. */
    class Code(
        val code: InviteCode,
    ) : InviteInput

    /** A link for the configured origin. */
    class Link(
        val code: InviteCode,
    ) : InviteInput

    /** A well-formed link for another origin. A release build shows "not for this server" and nothing else. */
    class OtherOrigin(
        val origin: String,
    ) : InviteInput

    data object Invalid : InviteInput
}

/**
 * Invite links, `<origin>/invite#<code>` (ADR-0010). One strict function, no URL library: general parsers normalise
 * case, default ports, user info and backslashes, and `https://real.example@evil.example/…` means different hosts to
 * different parsers (#17 plan review, SEC-7). After trimming ASCII whitespace the **whole** string must match, the
 * origin must be canonical, and the code must be canonical. No query-parameter fallback, nothing after the code.
 */
object InviteLinks {
    private const val PATH = "/invite#"
    private val LINK = Regex("""^(https://[a-z0-9.:-]+)/invite#([A-Za-z0-9_-]{22})$""")

    fun parse(
        raw: String,
        configuredOrigin: String,
    ): InviteInput {
        val text = raw.trim(::isAsciiSpace)
        val match = LINK.matchEntire(text)
        val code = (match?.groupValues?.get(2) ?: text).let(InviteCode::of)
        val origin = match?.groupValues?.get(1)
        return when {
            code == null -> InviteInput.Invalid
            match == null -> InviteInput.Code(code)
            origin == null || !ApiEndpoint.isCanonicalOrigin(origin) -> InviteInput.Invalid
            origin == configuredOrigin -> InviteInput.Link(code)
            else -> InviteInput.OtherOrigin(origin)
        }
    }

    /** The link the client shows and shares: built from the configured origin and the code, never taken from the server. */
    fun build(
        origin: String,
        code: InviteCode,
    ): String {
        require(ApiEndpoint.isCanonicalOrigin(origin)) { "origin must be canonical" }
        return origin + PATH + code.reveal()
    }

    private fun isAsciiSpace(c: Char) = c == ' ' || c in '\t'..'\r'
}
