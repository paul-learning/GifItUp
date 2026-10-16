package app.gifitup.server.invites

import app.gifitup.protocol.InviteStatus
import app.gifitup.server.auth.CanonicalOrigin
import app.gifitup.server.auth.InviteCodes
import app.gifitup.server.db.InviteRepository
import app.gifitup.server.db.InviteRow
import app.gifitup.server.db.NewInvite
import app.gifitup.server.db.TransactionRunner
import org.slf4j.LoggerFactory
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** A newly created invite. [link] carries the code and goes to the client once; it is never stored or logged. */
data class CreatedInvite(
    val id: UUID,
    val link: String,
    val expiresAt: Instant,
)

/** An invite in its creator's list, with its status at the time of the request. */
data class ListedInvite(
    val id: UUID,
    val createdAt: Instant,
    val expiresAt: Instant,
    val status: InviteStatus,
    val usedBy: String?,
)

sealed interface CreateOutcome {
    data class Created(
        val invite: CreatedInvite,
    ) : CreateOutcome

    /** Already [InviteService.MAX_OPEN] open invites. */
    data object LimitReached : CreateOutcome

    /** Already [InviteService.MAX_CREATED_PER_DAY] created in the last 24 hours. */
    data object RateLimited : CreateOutcome

    /** The account no longer exists (deleted while the session was still live). */
    data object NoAccount : CreateOutcome
}

/**
 * A member's own invites (ADR-0010): create, list, revoke. HTTP-agnostic; the account always comes from the
 * caller's session, never from the request. Admin invites (`created_by` NULL) are never anyone's (#13 SEC-52a).
 */
class InviteService(
    private val db: TransactionRunner,
    private val origin: CanonicalOrigin,
    private val clock: Clock,
    private val random: SecureRandom = SecureRandom(),
) {
    private val invites = InviteRepository()
    private val log = LoggerFactory.getLogger(InviteService::class.java)

    /**
     * Creates an invite valid for [LIFETIME]. Both limits are counted under a lock on the account row (#13 SEC-45,
     * SEC-51), so concurrent requests can't both pass the check.
     */
    fun create(accountId: UUID): CreateOutcome {
        val code = InviteCodes.generate(random)
        val outcome =
            db.transaction { c ->
                if (!invites.lockCreator(c, accountId)) return@transaction CreateOutcome.NoAccount
                // Read after the lock is held, so a request that waited counts with the current time (#75 SEC-64).
                val now = clock.instant()
                when {
                    invites.countOpen(c, accountId, now) >= MAX_OPEN -> {
                        CreateOutcome.LimitReached
                    }

                    invites.countCreatedSince(c, accountId, now.minus(CREATION_WINDOW)) >= MAX_CREATED_PER_DAY -> {
                        CreateOutcome.RateLimited
                    }

                    else -> {
                        val invite = NewInvite(UUID.randomUUID(), checkNotNull(InviteCodes.hash(code)), accountId, now, now.plus(LIFETIME))
                        invites.insert(c, invite)
                        CreateOutcome.Created(CreatedInvite(invite.id, InviteCodes.link(origin, code), invite.expiresAt))
                    }
                }
            }
        // No id, code, link or account in the log (CLAUDE.md security rule 4).
        if (outcome is CreateOutcome.Created) log.info("event=invite_created")
        return outcome
    }

    /** The caller's invites, newest first, at most [LIST_LIMIT] (#13 SEC-45). */
    fun list(accountId: UUID): List<ListedInvite> {
        val now = clock.instant()
        return db.transaction { c -> invites.listForCreator(c, accountId, LIST_LIMIT) }.map { it.listed(now) }
    }

    /** Revokes the caller's own open invite; false for anything else, without saying why. */
    fun revoke(
        accountId: UUID,
        inviteId: UUID,
    ): Boolean {
        val now = clock.instant()
        return db.transaction { c -> invites.revoke(c, inviteId, accountId, now) }
    }

    private fun InviteRow.listed(now: Instant): ListedInvite {
        val status =
            when {
                usedAt != null -> InviteStatus.USED
                revokedAt != null -> InviteStatus.REVOKED
                !expiresAt.isAfter(now) -> InviteStatus.EXPIRED
                else -> InviteStatus.OPEN
            }
        return ListedInvite(id, createdAt, expiresAt, status, usedBy.takeIf { status == InviteStatus.USED })
    }

    companion object {
        /** Owner decision 2026-10-16. */
        val LIFETIME: Duration = Duration.ofDays(7)
        const val MAX_OPEN = 5
        const val MAX_CREATED_PER_DAY = 20
        val CREATION_WINDOW: Duration = Duration.ofHours(24)
        const val LIST_LIMIT = 50
    }
}
