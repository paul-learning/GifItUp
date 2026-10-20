package app.gifitup.server.keypackages

import app.gifitup.keypackage.KeyPackageReader
import app.gifitup.keypackage.KeyPackageReading
import app.gifitup.server.auth.Base64Url
import app.gifitup.server.db.NewKeyPackage
import java.time.Duration
import java.time.Instant

internal sealed interface Parsed {
    class Items(
        val items: List<NewKeyPackage>,
    ) : Parsed

    class Refused(
        val reason: UploadRefusal,
    ) : Parsed
}

/** Why an upload was refused. Closed; only the name is logged, never sent to the client (ADR-0012). */
internal enum class UploadRefusal {
    ENCODING,
    DUPLICATE_IN_BATCH,
    NOT_ACCEPTED_BY_READER,
    WRONG_IDENTITY,
    WRONG_DEVICE_KEY,
    EXPIRES_TOO_SOON,
    NOT_YET_VALID,
    ALREADY_STORED,
}

/**
 * Checks an upload batch (ADR-0012): strict base64url, the strict reader, and that each KeyPackage belongs to the
 * uploading device (credential identity is the account's username, signature key is the device's registered key,
 * #12 SEC-11) and has a usable lifetime.
 */
internal object UploadParser {
    /** An upload must still have this long to live; a nearly dead KeyPackage is useless (ADR-0012). */
    private val MIN_REMAINING_LIFETIME = Duration.ofDays(MIN_REMAINING_DAYS)

    /** A client clock may run ahead of ours by this much (ADR-0012). */
    private val CLOCK_SLACK = Duration.ofHours(1)

    private const val MIN_REMAINING_DAYS = 7L

    private class One(
        val item: NewKeyPackage?,
        val refusal: UploadRefusal?,
    )

    fun parse(
        encoded: List<String>,
        username: ByteArray,
        deviceKey: ByteArray,
        now: Instant,
    ): Parsed {
        if (encoded.distinct().size != encoded.size) return Parsed.Refused(UploadRefusal.DUPLICATE_IN_BATCH)
        val ones = encoded.map { parseOne(it, username, deviceKey, now) }
        val refused = ones.firstNotNullOfOrNull { it.refusal }
        return if (refused != null) Parsed.Refused(refused) else Parsed.Items(ones.mapNotNull { it.item })
    }

    private fun parseOne(
        text: String,
        username: ByteArray,
        deviceKey: ByteArray,
        now: Instant,
    ): One {
        val bytes = Base64Url.decodeUpTo(text, KeyPackageReader.MAX_BYTES)
        val info = bytes?.let { (KeyPackageReader.read(it) as? KeyPackageReading.Accepted)?.info }
        val refusal =
            when {
                bytes == null -> UploadRefusal.ENCODING
                info == null -> UploadRefusal.NOT_ACCEPTED_BY_READER
                !info.identity.contentEquals(username) -> UploadRefusal.WRONG_IDENTITY
                !info.signatureKey.contentEquals(deviceKey) -> UploadRefusal.WRONG_DEVICE_KEY
                Instant.ofEpochSecond(info.notAfter) < now.plus(MIN_REMAINING_LIFETIME) -> UploadRefusal.EXPIRES_TOO_SOON
                Instant.ofEpochSecond(info.notBefore) > now.plus(CLOCK_SLACK) -> UploadRefusal.NOT_YET_VALID
                else -> null
            }
        val item =
            if (refusal == null && bytes != null &&
                info != null
            ) {
                NewKeyPackage(bytes, info.ciphersuite, Instant.ofEpochSecond(info.notAfter))
            } else {
                null
            }
        return One(item, refusal)
    }
}
