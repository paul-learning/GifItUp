package app.gifitup.cli

import app.gifitup.shared.domain.auth.AuthError
import app.gifitup.shared.domain.groups.DeliveredMessage
import app.gifitup.shared.domain.groups.GroupStatus
import app.gifitup.shared.domain.groups.RejectReason
import app.gifitup.shared.domain.groups.SyncOutcome
import app.gifitup.shared.domain.groups.WelcomeOutcome
import java.util.Base64

// The fixed text of the chat commands: one line per outcome, never server text, a key or a token.

internal fun welcomeLine(
    result: WelcomeOutcome,
    base64: Base64.Encoder,
): String =
    when (result) {
        is WelcomeOutcome.Joined -> {
            "joined group ${base64.encodeToString(result.groupId)}"
        }

        is WelcomeOutcome.AlreadyMember -> {
            "already in group ${base64.encodeToString(result.groupId)}"
        }

        is WelcomeOutcome.Skipped -> {
            "left a welcome for group ${base64.encodeToString(result.groupId)} pending"
        }

        is WelcomeOutcome.Rejected -> {
            when (result.reason) {
                RejectReason.UNUSABLE -> "dropped a welcome that could not be used"
                RejectReason.CONTACT_MISMATCH -> "dropped a welcome: a member's key does not match the contact book's pin"
            }
        }
    }

internal fun exitCodeOf(outcome: SyncOutcome): Int =
    when (outcome) {
        is SyncOutcome.Unavailable, is SyncOutcome.InFlight, is SyncOutcome.Rejected -> EXIT_SERVER
        else -> EXIT_REFUSED
    }

@Suppress("CyclomaticComplexMethod") // One fixed line per outcome.
internal fun failureLine(outcome: SyncOutcome): String =
    when (outcome) {
        is SyncOutcome.Sent, is SyncOutcome.CaughtUp -> "done"
        is SyncOutcome.Stopped -> statusLine(outcome.status)
        SyncOutcome.NotJoined -> "this device is not in that group"
        is SyncOutcome.Unavailable -> message(AuthError.Api(outcome.error))
        is SyncOutcome.InFlight -> "no final answer yet (${message(AuthError.Api(outcome.error))}); run chat-resume"
        is SyncOutcome.Rejected -> "the server refused it: ${message(AuthError.Api(outcome.error))}"
        is SyncOutcome.PendingFirst -> "an earlier message or commit is still pending: ${failureLine(outcome.outcome)}"
        SyncOutcome.CommitPending -> "a commit of this group is still pending; run chat-resume"
        SyncOutcome.InvalidRequest -> "that can't be done: unknown member, no KeyPackage left for it, or too many changes"
        SyncOutcome.TooLong -> "that line is longer than one message holds (896 bytes)"
        SyncOutcome.Busy -> "too many messages are waiting for a sync; run chat-sync first"
        is SyncOutcome.Retry -> "message ${outcome.seq} could not be decrypted yet; run chat-sync again"
        SyncOutcome.LookupIncomplete -> "the contact book is unavailable right now; nothing was applied"
        SyncOutcome.RekeyNeeded -> "this state dir's snapshot key reached its export limit"
        SyncOutcome.StorageFailed -> "the state dir couldn't be written; run the command again"
        is SyncOutcome.Poisoned -> "the MLS client failed"
    }

private fun statusLine(status: GroupStatus): String =
    when (status) {
        GroupStatus.ACTIVE -> "this group is active"
        GroupStatus.FROZEN_GAP -> "this group is frozen: messages are missing from its log; it has to be joined again"
        GroupStatus.FROZEN_COMMIT_REFUSED -> "this group is frozen: a commit broke a rule; it has to be joined again"
        GroupStatus.FROZEN_ENVELOPE_MISMATCH -> "this group is frozen: its membership data does not match; it has to be joined again"
        GroupStatus.FROZEN_CONTACT_MISMATCH -> "this group is frozen: a contact's key does not match its pin; see contacts"
        GroupStatus.READ_ONLY_ROLLBACK -> "this group is read-only: this state is older than a message the server accepted"
        GroupStatus.REMOVED -> "this group is no longer available"
    }

/**
 * The text for the terminal: control characters, line and paragraph separators and invisible format characters
 * (bidirectional overrides, zero-width marks) are shown as `?`, so a message can't move the cursor, fake a line or
 * reorder what is printed around it.
 */
internal fun printableText(message: DeliveredMessage): String = printable(message.plaintext.decodeToString())

internal fun printable(text: String): String = text.map { if (isHidden(it)) '?' else it }.joinToString("")

private fun isHidden(char: Char): Boolean =
    char.isISOControl() ||
        char.category == CharCategory.FORMAT ||
        char.category == CharCategory.LINE_SEPARATOR ||
        char.category == CharCategory.PARAGRAPH_SEPARATOR
