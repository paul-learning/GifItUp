package app.gifitup.cli

import app.gifitup.shared.data.network.Session
import app.gifitup.shared.domain.auth.AuthResult
import app.gifitup.shared.domain.groups.ChatActions
import app.gifitup.shared.domain.groups.IncomingSink
import app.gifitup.shared.domain.groups.SyncOutcome
import app.gifitup.shared.domain.messages.InMemoryMessageStore
import app.gifitup.shared.domain.messages.SendGif
import java.io.PrintStream
import java.util.Base64

/**
 * The group chat commands (ADR-0016, #22), thin over the app's [ChatActions]: argument handling and printing only. For
 * **test accounts only**: the CLI keeps its keys unprotected in the state dir, and `chat-sync` prints the decrypted text
 * of each message it reads, which is how a test sees that the round trip worked. Nothing readable leaves the machine:
 * the text is encrypted before it is posted. Group ids, usernames and counts are printed; keys, tokens and KeyPackages
 * never.
 */
internal class ChatCommands(
    private val chat: ChatActions,
    private val out: PrintStream,
    private val readText: () -> String,
    private val report: suspend (AuthResult.Failed) -> Int,
) {
    private val base64 = Base64.getUrlEncoder().withoutPadding()

    private val sendGif = SendGif(chat, InMemoryMessageStore())

    /** Prints each message the engine reads, whichever command it runs in: they are consumed once they are read. */
    private val sink =
        IncomingSink { message ->
            messageLine(message)?.let(out::println)
        }

    suspend fun run(
        invocation: Invocation,
        session: Session,
    ): Int {
        val group = invocation.group
        return when (invocation.command) {
            "chat-create" -> create(session, checkNotNull(invocation.target))
            "chat-add" -> change(session, checkNotNull(group), checkNotNull(invocation.target), add = true)
            "chat-remove" -> change(session, checkNotNull(group), checkNotNull(invocation.target), add = false)
            "chat-send" -> send(session, checkNotNull(group))
            "chat-sync" -> outcome(chat.sync(session, checkNotNull(group), sink)) { "synced, ${it.applied} log entries" }
            "chat-resume" -> outcome(chat.resume(session, checkNotNull(group), sink)) { "nothing was pending" }
            else -> welcomes(session)
        }
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
