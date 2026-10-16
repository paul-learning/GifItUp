package app.gifitup.protocol

import kotlinx.serialization.Serializable

// Invites (ADR-0010). All endpoints need a session; a member manages only the invites they created. Times are Unix
// seconds. Every field is required (ADR-0006 A7). The server never returns an invite code except once, inside [link].

/**
 * `POST /invites` → `201`. [link] is `<origin>/invite#<code>` and is shown once; the server keeps only a hash of the
 * code. The client accepts it only if it is exactly its own configured origin + `/invite#` + 22 characters of
 * canonical base64url (#13 SEC-50). `409 invite_limit` when 5 invites are already open; `429 rate_limited` after 20
 * created in 24 hours.
 */
@Serializable
data class CreateInviteResponse(
    val inviteId: String,
    val link: String,
    val expiresAt: Long,
)

/** `GET /invites`: the caller's own invites, newest first, at most 50. */
@Serializable
data class InviteListResponse(
    val invites: List<InviteInfo>,
)

/**
 * One invite as its creator sees it. [usedBy] is the username of the account it created, `null` unless [status] is
 * [InviteStatus.USED] (or that account was deleted since). That's how an inviter notices a link that reached the
 * wrong person (ADR-0010).
 */
@Serializable
data class InviteInfo(
    val inviteId: String,
    val createdAt: Long,
    val expiresAt: Long,
    val status: InviteStatus,
    val usedBy: String?,
)

/** Growable (ADR-0006): a client decodes a status it doesn't know as [UNKNOWN] and shows it as "not usable". */
@Serializable(with = InviteStatusSerializer::class)
enum class InviteStatus(
    val wire: String,
) {
    OPEN("open"),
    USED("used"),
    EXPIRED("expired"),
    REVOKED("revoked"),

    /** A status this build doesn't know. Never sent. */
    UNKNOWN("unknown"),
}

internal object InviteStatusSerializer : FallbackEnumSerializer<InviteStatus>(
    serialName = "app.gifitup.protocol.InviteStatus",
    values = InviteStatus.entries,
    fallback = InviteStatus.UNKNOWN,
    wireName = InviteStatus::wire,
)
