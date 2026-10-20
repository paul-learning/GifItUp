package app.gifitup.server.keypackages

import app.gifitup.server.auth.KeyedRateLimiter
import app.gifitup.server.db.AccountRepository
import app.gifitup.server.db.DeviceRepository
import app.gifitup.server.db.KeyPackageRepository
import app.gifitup.server.db.NewKeyPackage
import app.gifitup.server.db.TransactionRunner
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * The KeyPackage rate limits (ADR-0012), all keyed by account or device id, never an IP. [claimsByPair] holds up to
 * 100,000 keys (about 317 accounts claiming from each other); a full map refuses new keys.
 * - [uploads]: per device, burst 5, one more per 10 minutes. Failed uploads spend it too.
 * - [claimsByClaimer]: per claiming account, all targets: burst 20, one more per 3 minutes.
 * - [claimsByPair]: per claimer and target: burst 2, one more per 24 hours.
 * - [claimsByTarget]: per target, all claimers: burst 10, one more per hour.
 */
class KeyPackageLimits(
    clock: Clock,
    val uploads: KeyedRateLimiter = KeyedRateLimiter(clock, UPLOAD_BURST, Duration.ofMinutes(UPLOAD_REFILL_MINUTES)),
    val claimsByClaimer: KeyedRateLimiter = KeyedRateLimiter(clock, CLAIMER_BURST, Duration.ofMinutes(CLAIMER_REFILL_MINUTES)),
    val claimsByPair: KeyedRateLimiter = KeyedRateLimiter(clock, PAIR_BURST, Duration.ofHours(PAIR_REFILL_HOURS), PAIR_MAX_KEYS),
    val claimsByTarget: KeyedRateLimiter = KeyedRateLimiter(clock, TARGET_BURST, Duration.ofHours(TARGET_REFILL_HOURS)),
) {
    /** Per claimer, then per pair, then per target; a refusal gives the earlier tokens back and returns null. */
    fun takeClaim(
        claimer: UUID,
        target: UUID,
    ): ClaimTokens? {
        val taken = ClaimTokens()
        val allTaken =
            listOf(
                claimsByClaimer to claimer.toString(),
                claimsByPair to "$claimer|$target",
                claimsByTarget to target.toString(),
            ).all { (limiter, key) -> limiter.spend(key).also { if (it) taken.add(limiter, key) } }
        if (!allTaken) taken.giveBack()
        return taken.takeIf { allTaken }
    }

    private companion object {
        const val UPLOAD_BURST = 5
        const val UPLOAD_REFILL_MINUTES = 10L
        const val CLAIMER_BURST = 20
        const val CLAIMER_REFILL_MINUTES = 3L
        const val PAIR_BURST = 2
        const val PAIR_REFILL_HOURS = 24L
        const val PAIR_MAX_KEYS = 100_000
        const val TARGET_BURST = 10
        const val TARGET_REFILL_HOURS = 1L
    }
}

/** The claim tokens one claim holds; [giveBack] returns each exactly once when nothing was taken. */
class ClaimTokens internal constructor() {
    private val held = mutableListOf<Pair<KeyedRateLimiter, String>>()

    internal fun add(
        limiter: KeyedRateLimiter,
        key: String,
    ) {
        held += limiter to key
    }

    fun giveBack() = held.forEach { (limiter, key) -> limiter.refund(key) }
}

sealed interface UploadOutcome {
    /** Stored together; [available] is the device's unexpired KeyPackages now. */
    data class Accepted(
        val accepted: Int,
        val available: Int,
    ) : UploadOutcome

    /** Some KeyPackage is not acceptable (one answer for every reason; the reason is only logged). */
    data object Invalid : UploadOutcome

    /** The device would hold more than [KeyPackageService.MAX_UNEXPIRED] unexpired or [KeyPackageService.MAX_TOTAL] rows. */
    data object LimitReached : UploadOutcome

    data object RateLimited : UploadOutcome

    /** The device or its account no longer exists (deleted while the session was still live). */
    data object NoDevice : UploadOutcome
}

/** One device of the claimed account; [keyPackage] is null when it has none for the wanted suites. */
class ClaimedKeyPackage(
    val deviceId: UUID,
    val keyPackage: ByteArray?,
)

sealed interface ClaimOutcome {
    class Claimed(
        val devices: List<ClaimedKeyPackage>,
    ) : ClaimOutcome

    /** Unknown username and the claimer's own username: one answer, no probing. */
    data object NotFound : ClaimOutcome

    data object RateLimited : ClaimOutcome
}

/**
 * The KeyPackage directory (ADR-0012). HTTP-agnostic: the caller's account and device always come from the session,
 * never from the request. Logs only `event=` names with closed reasons: no account, device, username or bytes (rule 4).
 */
class KeyPackageService(
    private val db: TransactionRunner,
    private val clock: Clock,
    private val limits: KeyPackageLimits,
) {
    private val accounts = AccountRepository()
    private val devices = DeviceRepository()
    private val keyPackages = KeyPackageRepository()
    private val log = LoggerFactory.getLogger(KeyPackageService::class.java)

    /**
     * Stores [encoded] (base64url KeyPackages, 1 to [MAX_BATCH]) for the session's device, all or none. The reader
     * checks each one, and the server checks that it belongs to this device: the credential identity is the account's
     * username and the signature key is the device's registered key (#12 SEC-11, ADR-0012).
     */
    fun upload(
        deviceId: UUID,
        encoded: List<String>,
    ): UploadOutcome {
        require(encoded.size in 1..MAX_BATCH) { "1..$MAX_BATCH items" }
        // Charged on every attempt, also a failing one: the reader is not a free oracle (ADR-0012).
        val outcome =
            if (!limits.uploads.spend(deviceId.toString())) {
                UploadOutcome.RateLimited
            } else {
                val owner =
                    db.transaction { c ->
                        devices.find(c, deviceId)?.let { d -> accounts.usernameOf(c, d.accountId)?.let { d to it } }
                    }
                if (owner == null) {
                    UploadOutcome.NoDevice
                } else {
                    val now = clock.instant()
                    when (val parsed = UploadParser.parse(encoded, owner.second.encodeToByteArray(), owner.first.signatureKey, now)) {
                        is Parsed.Refused -> UploadOutcome.Invalid.also { logUpload("invalid", parsed.reason.name) }
                        is Parsed.Items -> store(deviceId, parsed.items, now)
                    }
                }
            }
        if (outcome !is UploadOutcome.Invalid) logUpload(outcomeName(outcome))
        return outcome
    }

    private fun outcomeName(outcome: UploadOutcome): String =
        when (outcome) {
            is UploadOutcome.Accepted -> "ok"
            UploadOutcome.Invalid -> "invalid"
            UploadOutcome.LimitReached -> "limit_reached"
            UploadOutcome.RateLimited -> "rate_limited"
            UploadOutcome.NoDevice -> "no_device"
        }

    /** The device's unexpired KeyPackages, or null if the device no longer exists. */
    fun available(deviceId: UUID): Int? =
        db.transaction { c -> if (devices.find(c, deviceId) == null) null else keyPackages.countUnexpired(c, deviceId, clock.instant()) }

    /**
     * One KeyPackage per device of [username] (at most [KeyPackageRepository.MAX_DEVICES]), removed from the directory.
     * [suites] is the claimer's preference, already validated. An unknown username and [claimer]'s own are one
     * [ClaimOutcome.NotFound], decided before any limit is touched (no probing, no bucket for a stranger's key).
     */
    fun claim(
        claimer: UUID,
        username: String,
        suites: List<Int>,
    ): ClaimOutcome {
        val target = db.transaction { c -> accounts.findIdByUsername(c, username) }
        val taken = if (target == null || target == claimer) null else limits.takeClaim(claimer, target)
        val outcome =
            when {
                target == null || target == claimer -> ClaimOutcome.NotFound
                taken == null -> ClaimOutcome.RateLimited
                else -> claimRows(target, suites, taken)
            }
        logClaim(
            when (outcome) {
                ClaimOutcome.NotFound -> "not_found"
                ClaimOutcome.RateLimited -> "rate_limited"
                is ClaimOutcome.Claimed -> if (outcome.devices.any { it.keyPackage != null }) "ok" else "empty"
            },
        )
        return outcome
    }

    private fun claimRows(
        target: UUID,
        suites: List<Int>,
        taken: ClaimTokens,
    ): ClaimOutcome =
        run {
            var done = false
            try {
                val floor = clock.instant().plus(CLAIM_FLOOR)
                val claimed =
                    db.transaction { c ->
                        keyPackages.deviceIds(c, target, KeyPackageRepository.MAX_DEVICES).map { device ->
                            ClaimedKeyPackage(device, keyPackages.claimOne(c, device, suites, floor))
                        }
                    }
                done = true
                // An empty pool is charged (D7): the tokens stay spent.
                ClaimOutcome.Claimed(claimed)
            } finally {
                // A failure rolled the transaction back, so nothing was taken: give every token back, once each.
                if (!done) taken.giveBack()
            }
        }

    /** Lock the device, count, insert; all or none. The lock makes concurrent uploads for one device count one after the other. */
    private fun store(
        deviceId: UUID,
        items: List<NewKeyPackage>,
        now: Instant,
    ): UploadOutcome =
        try {
            db.transaction { c -> storeLocked(c, deviceId, items, now) }
        } catch (e: AlreadyStored) {
            logUpload("invalid", e.reason.name)
            UploadOutcome.Invalid
        }

    private fun storeLocked(
        c: java.sql.Connection,
        deviceId: UUID,
        items: List<NewKeyPackage>,
        now: Instant,
    ): UploadOutcome {
        val locked = keyPackages.lockDevice(c, deviceId)
        val unexpired = if (locked) keyPackages.countUnexpired(c, deviceId, now) else 0
        return when {
            !locked -> {
                UploadOutcome.NoDevice
            }

            unexpired + items.size > MAX_UNEXPIRED || keyPackages.countAll(c, deviceId) + items.size > MAX_TOTAL -> {
                UploadOutcome.LimitReached
            }

            keyPackages.insert(c, deviceId, items, now) != items.size -> {
                throw AlreadyStored()
            }

            else -> {
                UploadOutcome.Accepted(items.size, unexpired + items.size)
            }
        }
    }

    /** Control flow only (rolls the batch back): no message, no stack trace. */
    private class AlreadyStored(
        val reason: UploadRefusal = UploadRefusal.ALREADY_STORED,
    ) : RuntimeException(null, null, false, false)

    private fun logUpload(
        result: String,
        reason: String? = null,
    ) {
        if (reason == null) {
            log.info("event=key_package_upload result={}", result)
        } else {
            log.info("event=key_package_upload result={} reason={}", result, reason)
        }
    }

    private fun logClaim(result: String) = log.info("event=key_package_claim result={}", result)

    companion object {
        const val MAX_BATCH = 50

        /** The crate's held cap (ADR-0007): a device never has more than this many unexpired KeyPackages. */
        const val MAX_UNEXPIRED = 200

        /** Expired rows not yet cleaned count too (ADR-0012). */
        const val MAX_TOTAL = 400

        /** What a client is told to refill below (ADR-0012); clients clamp it to 1..200. */
        const val LOW_WATER_MARK = 20

        /** A claim skips KeyPackages that expire within a day; the cleanup only deletes ones already expired. */
        private val CLAIM_FLOOR = Duration.ofDays(1)
    }
}
