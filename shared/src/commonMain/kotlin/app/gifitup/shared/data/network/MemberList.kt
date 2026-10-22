package app.gifitup.shared.data.network

import app.gifitup.protocol.MemberInfo
import app.gifitup.protocol.MemberRelation
import app.gifitup.protocol.MembersResponse

/** How the server says a listed account relates to the caller. Display only: no check depends on it (ADR-0014). */
enum class MemberRelationship { INVITER, INVITEE, UNKNOWN }

/** One device of a listed member: its id (a label) and its signature key (32 bytes, the value a contact pins). */
class MemberDeviceKey(
    val deviceId: String,
    val signatureKey: ByteArray,
)

/** A listed member as the server said it. Untrusted: the contact book checks it before it pins anything. */
class MemberEntry(
    val accountId: String,
    val username: String,
    val relationship: MemberRelationship,
    val devices: List<MemberDeviceKey>,
)

/** The answer of `GET /members`. [more] is true if the server cut the list: absence from it proves nothing then. */
class MemberList(
    val members: List<MemberEntry>,
    val more: Boolean,
)

private const val MAX_MEMBERS = 200
private const val MAX_DEVICES_PER_MEMBER = 10
private const val SIGNATURE_KEY_BYTES = 32

/**
 * The checked list, or null (the whole answer is refused, ADR-0014): at most [MAX_MEMBERS] members with at most
 * [MAX_DEVICES_PER_MEMBER] devices each, canonical UUIDs, the username rule, canonical base64url 32-byte keys, and
 * at most one inviter, every account id, username, device id and key appearing once in the whole answer. An unknown relation is kept as
 * [MemberRelationship.UNKNOWN].
 */
internal fun readMembers(response: MembersResponse): MemberList? {
    val read = response.members.takeIf { it.size <= MAX_MEMBERS }?.map(::readMember)
    val members = read?.filterNotNull()?.takeIf { it.size == read.size }
    val devices = members?.flatMap { it.devices }.orEmpty()
    val unique =
        members != null && members.unique { it.accountId } && members.unique { it.username } &&
            devices.unique { it.deviceId } && devices.unique { it.signatureKey.asList() } &&
            members.count { it.relationship == MemberRelationship.INVITER } <= 1
    return if (unique) MemberList(members, response.more) else null
}

private fun readMember(info: MemberInfo): MemberEntry? {
    val devices =
        info.devices.takeIf { it.size <= MAX_DEVICES_PER_MEMBER }?.map { device ->
            val key = Base64Url.decode(device.signatureKey, SIGNATURE_KEY_BYTES..SIGNATURE_KEY_BYTES)
            if (GifItUpApi.UUID.matches(device.deviceId) && key != null) MemberDeviceKey(device.deviceId, key) else null
        }
    return if (GifItUpApi.UUID.matches(info.accountId) && GifItUpApi.USERNAME.matches(info.username) &&
        devices?.all { it != null } == true
    ) {
        MemberEntry(info.accountId, info.username, relationshipOf(info.relation), devices.filterNotNull())
    } else {
        null
    }
}

private fun relationshipOf(relation: MemberRelation): MemberRelationship =
    when (relation) {
        MemberRelation.INVITER -> MemberRelationship.INVITER
        MemberRelation.INVITEE -> MemberRelationship.INVITEE
        MemberRelation.UNKNOWN -> MemberRelationship.UNKNOWN
    }

private fun <T, K> List<T>.unique(key: (T) -> K): Boolean = map(key).toSet().size == size
