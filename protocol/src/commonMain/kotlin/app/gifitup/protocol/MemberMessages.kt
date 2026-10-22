package app.gifitup.protocol

import kotlinx.serialization.Serializable

// Members (ADR-0014, #18). `GET /members` needs a session and takes no parameters. Every field is required (ADR-0006
// A7). The list is a convenience view of the caller's invite neighbours, not an access control: any member can still
// be reached by username through the KeyPackage claim (ADR-0012).

/**
 * `GET /members` → `200`: the caller's inviter (first, if any) and the accounts the caller invited, then by username.
 * At most 200 [members]; [more] is true when the list was cut, and a client must not conclude from a cut list that
 * an account is gone. The server's statement only: nothing in it lowers a client's checks (ADR-0014).
 */
@Serializable
data class MembersResponse(
    val members: List<MemberInfo>,
    val more: Boolean,
)

/** One neighbour. [accountId] is the account's random id (a UUID); [devices] has at most 10 entries, oldest first. */
@Serializable
data class MemberInfo(
    val accountId: String,
    val username: String,
    val relation: MemberRelation,
    val devices: List<MemberDevice>,
)

/** [signatureKey] is the device's registered Ed25519 public key: 32 bytes, canonical base64url without padding. */
@Serializable
data class MemberDevice(
    val deviceId: String,
    val signatureKey: String,
)

/** Growable (ADR-0006): a client decodes a relation it doesn't know as [UNKNOWN]. Display only, never trust. */
@Serializable(with = MemberRelationSerializer::class)
enum class MemberRelation(
    val wire: String,
) {
    /** The account whose invite the caller joined with. */
    INVITER("inviter"),

    /** An account that joined with an invite the caller created. */
    INVITEE("invitee"),

    /** A relation this build doesn't know. Never sent. */
    UNKNOWN("unknown"),
}

internal object MemberRelationSerializer : FallbackEnumSerializer<MemberRelation>(
    serialName = "app.gifitup.protocol.MemberRelation",
    values = MemberRelation.entries,
    fallback = MemberRelation.UNKNOWN,
    wireName = MemberRelation::wire,
)
