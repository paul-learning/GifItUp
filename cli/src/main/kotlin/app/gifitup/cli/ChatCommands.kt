package app.gifitup.cli

import app.gifitup.shared.data.network.Session
import app.gifitup.shared.domain.auth.AuthResult
import app.gifitup.shared.domain.contacts.KeyBytes
import app.gifitup.shared.domain.groups.ChatActions
import app.gifitup.shared.domain.groups.IncomingSink
import app.gifitup.shared.domain.groups.SyncOutcome
import app.gifitup.shared.domain.messages.EncryptedMessageStore
import app.gifitup.shared.domain.messages.MessageBody
import app.gifitup.shared.domain.messages.MessageIntake
import app.gifitup.shared.domain.messages.SendGif
import java.io.PrintStream
import java.util.Base64
import kotlin.time.Clock

/**
 * The group chat commands (ADR-0016, #22), thin over the app's [ChatActions]: argument handling and printing only. For
 * **test accounts only**: the CLI keeps its keys unprotected in the state dir, and `chat-sync` prints the decrypted text
 * of each message it reads, which is how a test sees that the round trip worked. Nothing readable leaves the machine:
 * the text is encrypted before it is posted. Group ids, usernames and counts are printed; keys, tokens and KeyPackages
 * never.
 */
@Suppress("TooManyFunctions") // One small function per command of the chat family.
internal class ChatCommands(
    private val chat: ChatActions,
    private val store: EncryptedMessageStore,
    private val out: PrintStream,
    private val readText: () -> String,
    private val report: suspend (AuthResult.Failed) -> Int,
) {
    private val base64 = Base64.getUrlEncoder().withoutPadding()

    private val sendGif = SendGif(chat, store) { Clock.System.now().epochSeconds }

    /**
     * Stores, then prints, each message the engine reads, whichever command it runs in: they are consumed once they are
     * read. The store goes first and its failure is **not** caught here: it reaches the engine, which stops (ADR-0017), and
     * the line of a message that was not stored is never printed.
     */
    private val sink = StoringSink(MessageIntake(store) { Clock.System.now().epochSeconds }, out)

    suspend fun run(
        invocation: Invocation,
        session: Session,
    ): Int {
        val group = invocation.group
        return when (invocation.command) {
            "chat-create" -> {
                create(session, checkNotNull(invocation.target))
            }

            "chat-add" -> {
                change(session, checkNotNull(group), checkNotNull(invocation.target), add = true)
            }

            "chat-remove" -> {
                change(session, checkNotNull(group), checkNotNull(invocation.target), add = false)
            }

            "chat-send" -> {
                send(session, checkNotNull(group))
            }

            "chat-rename" -> {
                rename(session, checkNotNull(group))
            }

            "chat-react" -> {
                react(session, checkNotNull(group), checkNotNull(invocation.seq))
            }

            "chat-sync" -> {
                outcome(chat.sync(session, checkNotNull(group), sink)) { "synced, ${it.applied} log entries" }
            }

            "chat-resume" -> {
                outcome(chat.resume(session, checkNotNull(group), sink)) { "nothing was pending" }
            }

            "chat-groups" -> {
                groups(session)
            }

            else -> {
                requestOrWelcomes(invocation.command, session, group)
            }
        }
    }

    /** The request commands (#279), and the welcomes as the last command of the family. */
    private suspend fun requestOrWelcomes(
        command: String,
        session: Session,
        group: ByteArray?,
    ): Int =
        when (command) {
            "chat-requests" -> requests(session)
            "chat-accept" -> acceptRequest(session, checkNotNull(group))
            "chat-decline" -> leave(session, checkNotNull(group), onlyRequests = true)
            "chat-leave" -> leave(session, checkNotNull(group), onlyRequests = false)
            else -> welcomes(session)
        }

    private suspend fun create(
        session: Session,
        target: String,
    ): Int =
        when (val result = chat.create(session, target, sink)) {
            is AuthResult.Failed -> {
                report(result)
            }

            is AuthResult.Ok -> {
                val groupId = result.value.groupId
                if (groupId != null && result.value.outcome !is SyncOutcome.Sent) {
                    out.println("group ${base64.encodeToString(groupId)} exists on this device; run chat-resume to finish it")
                }
                finish(result.value.outcome) { sent -> "created group ${groupId?.let(base64::encodeToString)} at seq ${sent.seq}" }
            }
        }

    private suspend fun change(
        session: Session,
        group: ByteArray,
        target: String,
        add: Boolean,
    ): Int {
        val result = if (add) chat.add(session, group, target, sink) else chat.remove(session, group, target, sink)
        return outcome(result) { sent -> (if (add) "added" else "removed") + " the devices of $target, commit at seq ${sent.seq}" }
    }

    private suspend fun send(
        session: Session,
        group: ByteArray,
    ): Int = outcome(sendGif(session, group, TEST_GIF, readText().ifEmpty { null }, sink)) { sent -> "sent at seq ${sent.seq}" }

    /** Names the group with the line on stdin (a `group_meta` message); the chat counts as named once the server accepted it. */
    private suspend fun rename(
        session: Session,
        group: ByteArray,
    ): Int = outcome(sendGif.rename(session, group, readText(), sink)) { sent -> "renamed at seq ${sent.seq}" }

    /**
     * Reacts to the stored GIF with the server order number [seq] with the emoji on stdin (an empty line takes the reaction back),
     * through the app's own use case; the reaction shows in `chat-history` once the server accepted it (ADR-0006 B10a).
     */
    private suspend fun react(
        session: Session,
        group: ByteArray,
        seq: Long,
    ): Int {
        val target =
            store
                .messages(KeyBytes(group))
                .firstOrNull { it.seq == seq && it.body is MessageBody.Gif }
                ?.reference
                ?: throw CliError("no stored GIF with that seq in this group (see chat-history)", EXIT_REFUSED)
        val emoji = readText().ifEmpty { null }
        return when (val groups = chat.groups(session, sink)) {
            is AuthResult.Failed -> {
                report(groups)
            }

            is AuthResult.Ok -> {
                val result = sendGif.react(session, group, target, emoji, groups.value.own, sink)
                if (result is AuthResult.Ok && result.value is SyncOutcome.InvalidRequest) throw CliError(NO_CHANGE_LINE, EXIT_REFUSED)
                outcome(result) { sent -> (if (emoji == null) "reaction taken back" else "reacted") + " at seq ${sent.seq}" }
            }
        }
    }

    private suspend fun groups(session: Session): Int =
        when (val result = chat.groups(session, sink)) {
            is AuthResult.Failed -> {
                report(result)
            }

            is AuthResult.Ok -> {
                if (result.value.groups.isEmpty()) out.println("no groups")
                result.value.groups.forEach {
                    out.println(
                        if (it.requestedBy != null) {
                            "group ${base64.encodeToString(it.id.bytes)} request from ${it.requestedBy}"
                        } else {
                            "group ${base64.encodeToString(it.id.bytes)} ${it.status.name.lowercase()} with ${it.others.joinToString(",")}"
                        },
                    )
                }
                EXIT_OK
            }
        }

    private suspend fun acceptRequest(
        session: Session,
        group: ByteArray,
    ): Int = outcome(chat.acceptRequest(session, group, sink)) { "accepted group ${base64.encodeToString(group)}" }

    private suspend fun requests(session: Session): Int =
        when (val result = chat.groups(session, sink)) {
            is AuthResult.Failed -> {
                report(result)
            }

            is AuthResult.Ok -> {
                val asked = result.value.groups.filter { it.requestedBy != null }
                if (asked.isEmpty()) out.println("no requests")
                asked.forEach { out.println("request ${base64.encodeToString(it.id.bytes)} from ${it.requestedBy}") }
                EXIT_OK
            }
        }

    /** Leaves [group] and deletes its history; with [onlyRequests] only a request can be declined this way. */
    private suspend fun leave(
        session: Session,
        group: ByteArray,
        onlyRequests: Boolean,
    ): Int {
        val groups = (chat.groups(session, sink) as? AuthResult.Ok)?.value?.groups
        val info = groups?.firstOrNull { it.id.bytes.contentEquals(group) }
        return when {
            info == null -> finish(SyncOutcome.NotJoined) { "" }
            onlyRequests && info.requestedBy == null -> finish(SyncOutcome.InvalidRequest) { "" }
            else -> leaveChat(session, group, if (onlyRequests) "declined" else "left")
        }
    }

    private suspend fun leaveChat(
        session: Session,
        group: ByteArray,
        verb: String,
    ): Int =
        when (val left = chat.leave(session, group, sink)) {
            is AuthResult.Failed -> {
                report(left)
            }

            is AuthResult.Ok -> {
                if (left.value is SyncOutcome.CaughtUp) store.forget(KeyBytes(group))
                finish(left.value) { "$verb group ${base64.encodeToString(group)}" }
            }
        }

    private suspend fun welcomes(session: Session): Int =
        when (val result = chat.welcomes(session, sink)) {
            is AuthResult.Failed -> {
                report(result)
            }

            is AuthResult.Ok -> {
                if (result.value.results.isEmpty() && result.value.stoppedBy == null) out.println("no pending welcomes")
                result.value.results.forEach { out.println(welcomeLine(it, base64)) }
                result.value.stoppedBy?.let { finish(it) { _ -> "" } } ?: EXIT_OK
            }
        }

    private suspend fun <T : SyncOutcome> outcome(
        result: AuthResult<T>,
        onSuccess: (Success) -> String,
    ): Int =
        when (result) {
            is AuthResult.Failed -> report(result)
            is AuthResult.Ok -> finish(result.value, onSuccess)
        }

    /** What the success lines can use: the seq of a sent message or commit, or the number of log entries applied. */
    class Success(
        val seq: Long,
        val applied: Int,
    )

    private fun finish(
        outcome: SyncOutcome,
        onSuccess: (Success) -> String,
    ): Int =
        when (outcome) {
            is SyncOutcome.Sent -> done(onSuccess(Success(outcome.seq, 0)))
            is SyncOutcome.CaughtUp -> done(onSuccess(Success(0, outcome.applied)))
            else -> throw CliError(failureLine(outcome), exitCodeOf(outcome))
        }

    private fun done(line: String): Int {
        out.println(line)
        return EXIT_OK
    }
}

private const val NO_CHANGE_LINE =
    "that can't be done: the reaction changes nothing " +
        "(the same emoji again, a message that is not a GIF, or 32 members have reacted already)"
