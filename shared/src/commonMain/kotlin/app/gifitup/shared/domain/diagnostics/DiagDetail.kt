@file:Suppress("MagicNumber") // The stored codes are the content of this file.

package app.gifitup.shared.domain.diagnostics

import app.gifitup.shared.data.network.ApiError
import app.gifitup.shared.domain.auth.AuthError
import app.gifitup.shared.domain.events.StopReason
import app.gifitup.shared.domain.groups.RejectReason
import app.gifitup.shared.domain.media.UnavailableReason

/**
 * The "why" next to a [DiagEvent] (the debug log, #154 follow-up): one value of a closed set, each made from a closed enum the
 * app already has (the API's failure kinds, the local failure kinds, the reasons a welcome is rejected, why live updates
 * stopped, why a GIF didn't load). Like [DiagEvent] it is stored by [code], which never changes and is never reused. The
 * `diag()` functions below are **exhaustive** `when`s on purpose: a new value of any of those enums doesn't compile until
 * someone has decided here whether it may be logged (a value whose moment would show who the user talks to is mapped to
 * `null`, never to a code).
 */
enum class DiagDetail(
    val code: Int,
    val label: String,
) {
    API_BAD_REQUEST(100, "bad request"),
    API_CLIENT_OUTDATED(101, "client outdated"),
    API_SERVER_OUTDATED(102, "server outdated"),
    API_UNAUTHORIZED(103, "unauthorized"),
    API_INVITE_INVALID(104, "invite invalid"),
    API_USERNAME_UNAVAILABLE(105, "username unavailable"),
    API_RATE_LIMITED(106, "rate limited"),
    API_NOT_FOUND(107, "not found"),
    API_KEY_PACKAGE_INVALID(108, "key package invalid"),
    API_KEY_PACKAGE_LIMIT(109, "key package limit"),
    API_INVITE_LIMIT(110, "invite limit"),
    API_ENVELOPE_INVALID(111, "envelope invalid"),
    API_ENVELOPE_CONFLICT(112, "envelope conflict"),
    API_EPOCH_CONFLICT(113, "epoch conflict"),
    API_GROUP_FULL(114, "group full"),
    API_WELCOME_LIMIT(115, "welcome limit"),
    API_GROUP_LIMIT(116, "group limit"),
    API_PAYLOAD_TOO_LARGE(117, "payload too large"),
    API_SERVER_ERROR(118, "server error"),
    API_MALFORMED_RESPONSE(119, "malformed response"),
    API_NETWORK(120, "network"),
    LOCAL_INVALID_USERNAME(200, "invalid username"),
    LOCAL_INVALID_INVITE_CODE(201, "invalid invite code"),
    LOCAL_ALREADY_REGISTERED(202, "already registered"),
    LOCAL_NOT_REGISTERED(203, "not registered"),
    LOCAL_USERNAME_MISMATCH(204, "username mismatch"),
    LOCAL_OTHER_ORIGIN(205, "other origin"),
    LOCAL_STATE_INVALID(206, "state invalid"),
    LOCAL_CRYPTO(207, "crypto"),
    LOCAL_KEY_UNAVAILABLE(208, "key unavailable"),
    LOCAL_RESTORE_BLOCKED(209, "restore blocked"),
    LOCAL_STORAGE_FAILED(210, "storage failed"),
    LOCAL_CONTACTS_UNAVAILABLE(211, "contacts unavailable"),
    LOCAL_CONTACT_BLOCKED(212, "contact blocked"),
    LOCAL_UNEXPECTED(213, "unexpected"),
    SYNC_RETRY(300, "will retry"),
    SYNC_REKEY_NEEDED(301, "new keys needed"),
    SYNC_COMMIT_PENDING(302, "a change is waiting"),
    SYNC_LOOKUP_INCOMPLETE(303, "lookup incomplete"),
    SYNC_BUSY(304, "busy"),
    SYNC_STORAGE_FAILED(305, "device storage failed"),
    SYNC_POISONED(306, "crypto stopped"),
    SYNC_STOPPED(307, "chat stopped"),
    WELCOME_STALE(400, "stale"),
    WELCOME_UNUSABLE(401, "unusable"),
    STOP_OUTDATED_CLIENT(500, "app too old"),
    STOP_PROTOCOL_ERROR(501, "protocol error"),
    STOP_SESSION_REFUSED(502, "session refused"),
    STOP_NOT_SIGNED_IN(503, "not signed in"),
    MEDIA_FETCH_FAILED(600, "download failed"),
    MEDIA_TOO_LARGE(601, "too large"),
    MEDIA_REJECTED(602, "not a usable gif"),
    LIVE_CONNECTING(700, "connecting"),
    LIVE_WAITING(701, "waiting to retry"),
    ;

    companion object {
        private val byCode = entries.associateBy { it.code }

        /** The detail stored under [code], or `null` for a code this build doesn't know. */
        fun ofCode(code: Int): DiagDetail? = byCode[code]
    }
}

/** The detail for an API failure kind. Every kind is a system condition, none says who the user talked to. */
@Suppress("CyclomaticComplexMethod") // One branch per kind: the exhaustive `when` is the point (see above).
fun ApiError.diag(): DiagDetail =
    when (this) {
        ApiError.BAD_REQUEST -> DiagDetail.API_BAD_REQUEST
        ApiError.CLIENT_OUTDATED -> DiagDetail.API_CLIENT_OUTDATED
        ApiError.SERVER_OUTDATED -> DiagDetail.API_SERVER_OUTDATED
        ApiError.UNAUTHORIZED -> DiagDetail.API_UNAUTHORIZED
        ApiError.INVITE_INVALID -> DiagDetail.API_INVITE_INVALID
        ApiError.USERNAME_UNAVAILABLE -> DiagDetail.API_USERNAME_UNAVAILABLE
        ApiError.RATE_LIMITED -> DiagDetail.API_RATE_LIMITED
        ApiError.NOT_FOUND -> DiagDetail.API_NOT_FOUND
        ApiError.KEY_PACKAGE_INVALID -> DiagDetail.API_KEY_PACKAGE_INVALID
        ApiError.KEY_PACKAGE_LIMIT -> DiagDetail.API_KEY_PACKAGE_LIMIT
        ApiError.INVITE_LIMIT -> DiagDetail.API_INVITE_LIMIT
        ApiError.ENVELOPE_INVALID -> DiagDetail.API_ENVELOPE_INVALID
        ApiError.ENVELOPE_CONFLICT -> DiagDetail.API_ENVELOPE_CONFLICT
        ApiError.EPOCH_CONFLICT -> DiagDetail.API_EPOCH_CONFLICT
        ApiError.GROUP_FULL -> DiagDetail.API_GROUP_FULL
        ApiError.WELCOME_LIMIT -> DiagDetail.API_WELCOME_LIMIT
        ApiError.GROUP_LIMIT -> DiagDetail.API_GROUP_LIMIT
        ApiError.PAYLOAD_TOO_LARGE -> DiagDetail.API_PAYLOAD_TOO_LARGE
        ApiError.SERVER_ERROR -> DiagDetail.API_SERVER_ERROR
        ApiError.MALFORMED_RESPONSE -> DiagDetail.API_MALFORMED_RESPONSE
        ApiError.NETWORK -> DiagDetail.API_NETWORK
    }

/** The detail for a local failure kind. */
@Suppress("CyclomaticComplexMethod") // One branch per kind: the exhaustive `when` is the point (see above).
fun AuthError.Local.diag(): DiagDetail =
    when (this) {
        AuthError.Local.INVALID_USERNAME -> DiagDetail.LOCAL_INVALID_USERNAME
        AuthError.Local.INVALID_INVITE_CODE -> DiagDetail.LOCAL_INVALID_INVITE_CODE
        AuthError.Local.ALREADY_REGISTERED -> DiagDetail.LOCAL_ALREADY_REGISTERED
        AuthError.Local.NOT_REGISTERED -> DiagDetail.LOCAL_NOT_REGISTERED
        AuthError.Local.USERNAME_MISMATCH -> DiagDetail.LOCAL_USERNAME_MISMATCH
        AuthError.Local.OTHER_ORIGIN -> DiagDetail.LOCAL_OTHER_ORIGIN
        AuthError.Local.STATE_INVALID -> DiagDetail.LOCAL_STATE_INVALID
        AuthError.Local.CRYPTO -> DiagDetail.LOCAL_CRYPTO
        AuthError.Local.KEY_UNAVAILABLE -> DiagDetail.LOCAL_KEY_UNAVAILABLE
        AuthError.Local.RESTORE_BLOCKED -> DiagDetail.LOCAL_RESTORE_BLOCKED
        AuthError.Local.STORAGE_FAILED -> DiagDetail.LOCAL_STORAGE_FAILED
        AuthError.Local.CONTACTS_UNAVAILABLE -> DiagDetail.LOCAL_CONTACTS_UNAVAILABLE
        AuthError.Local.CONTACT_BLOCKED -> DiagDetail.LOCAL_CONTACT_BLOCKED
        AuthError.Local.UNEXPECTED -> DiagDetail.LOCAL_UNEXPECTED
    }

/** Why live updates ended for good. */
fun StopReason.diag(): DiagDetail =
    when (this) {
        StopReason.OUTDATED_CLIENT -> DiagDetail.STOP_OUTDATED_CLIENT
        StopReason.PROTOCOL_ERROR -> DiagDetail.STOP_PROTOCOL_ERROR
        StopReason.SESSION_REFUSED -> DiagDetail.STOP_SESSION_REFUSED
        StopReason.NOT_SIGNED_IN -> DiagDetail.STOP_NOT_SIGNED_IN
    }

/**
 * Why a welcome was rejected. `CONTACT_MISMATCH` and `REQUEST_LIMIT` are **not** logged (`null`): the first happens when a
 * particular person's invitation doesn't match what the user pinned, the second when someone sent many chat requests; their
 * moment says too much about a contact.
 */
fun RejectReason.diag(): DiagDetail? =
    when (this) {
        RejectReason.STALE -> DiagDetail.WELCOME_STALE
        RejectReason.UNUSABLE -> DiagDetail.WELCOME_UNUSABLE
        RejectReason.CONTACT_MISMATCH, RejectReason.REQUEST_LIMIT -> null
    }

/** Why a GIF couldn't be shown. */
fun UnavailableReason.diag(): DiagDetail =
    when (this) {
        UnavailableReason.FETCH_FAILED -> DiagDetail.MEDIA_FETCH_FAILED
        UnavailableReason.TOO_LARGE -> DiagDetail.MEDIA_TOO_LARGE
        UnavailableReason.REJECTED -> DiagDetail.MEDIA_REJECTED
    }
