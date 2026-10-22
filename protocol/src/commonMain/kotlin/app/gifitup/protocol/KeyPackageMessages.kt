package app.gifitup.protocol

import kotlinx.serialization.Serializable

// KeyPackage directory (ADR-0012). All endpoints need a session. KeyPackages travel as MLSMessage bytes in base64url
// without padding; the server reads them at upload (ADR-0012) and never hands out anything it did not store. Every
// field is required (ADR-0006 A7).

/**
 * `POST /key-packages`: the caller's own device uploads 1 to 50 KeyPackages. The whole batch is checked first and
 * stored together or not at all. Each item is at most 2731 characters (2048 bytes, unpadded base64url, canonical:
 * no padding and no trailing bits). `400 key_package_invalid` when any item is not acceptable (one code, no detail),
 * `409 key_package_limit` when the device would hold more than the limit, `429 rate_limited`,
 * `413 payload_too_large`.
 */
@Serializable
data class UploadKeyPackagesRequest(
    val keyPackages: List<String>,
)

/**
 * `POST /key-packages` → `200`. [available] counts the device's unexpired KeyPackages after the upload.
 * [lowWaterMark] is the server's advice: refill when [available] is below it. It is untrusted: a client clamps it to
 * 1..200 (ADR-0012).
 */
@Serializable
data class UploadKeyPackagesResponse(
    val accepted: Int,
    val available: Int,
    val lowWaterMark: Int,
)

/** `GET /key-packages/status` → `200`, for the caller's own device. Same meaning as in [UploadKeyPackagesResponse]. */
@Serializable
data class KeyPackageStatusResponse(
    val available: Int,
    val lowWaterMark: Int,
)

/**
 * `POST /key-packages/claim`: takes one KeyPackage per device of [username] and removes it from the directory (single
 * use, RFC 9420 §16.8). [ciphersuites] is the claimer's preference, most wanted first: 1 to 4 distinct MLS cipher
 * suite ids, each 1..65535. Per device the server picks the KeyPackage of the first suite in the list that it has, the
 * oldest one of that suite; an id nobody uploaded just finds none. A malformed [username] or list is `400` and spends
 * no claim token. An unknown username and the caller's own username are the same `404 not_found`.
 * `429 rate_limited` when a claim limit is used up. A lost response loses the KeyPackage, and a claim is never
 * repeated automatically (ADR-0012).
 */
@Serializable
data class ClaimKeyPackagesRequest(
    val username: String,
    val ciphersuites: List<Int>,
)

/**
 * `POST /key-packages/claim` → `200`: at most 10 devices, oldest first (a safety bound; one device per account in
 * v1). The response carries no cipher suite and no count of what is left: the client reads the suite from the bytes
 * and checks every KeyPackage itself (`inspect_key_package`, ADR-0002). Byte fields are canonical base64url, which
 * the client decodes strictly. [accountId] (ADR-0014) is the claimed account's id, so the client can tell a new device
 * of a known account from a new account under the same name. It is required: a response without it fails to decode,
 * and the client's contact check treats that as a refusal, never as "unknown, so fine".
 */
@Serializable
data class ClaimKeyPackagesResponse(
    val accountId: String,
    val devices: List<ClaimedDevice>,
)

/** One device of the claimed user. [keyPackage] is `null` when the device has none for the wanted suites. */
@Serializable
data class ClaimedDevice(
    val deviceId: String,
    val keyPackage: String?,
)
