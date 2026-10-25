package app.gifitup.cli

import app.gifitup.shared.data.crypto.SecureRandomSource
import app.gifitup.shared.data.network.ApiError
import app.gifitup.shared.data.network.ApiResult
import app.gifitup.shared.data.network.DeliveryEnvelope
import app.gifitup.shared.data.network.GifItUpApi
import app.gifitup.shared.data.network.MessageKind
import app.gifitup.shared.data.network.Session
import java.io.PrintStream
import java.util.Base64

/**
 * The Delivery Service commands (ADR-0015, #21), a wire-level test driver for **test accounts only**: they send random
 * bytes as commit, welcome and application payloads (and the text given to `group-post`), because the server never
 * parses them. Real MLS content comes with the client sync loop (#22). Output is one fixed line per result; it shows ids
 * and counts, never tokens or keys.
 */
@Suppress("TooManyFunctions") // One function per command.
internal class DeliveryCommands(
    private val api: GifItUpApi,
    private val random: SecureRandomSource,
    private val out: PrintStream,
    private val fail: (ApiError) -> Nothing,
) {
    private val base64 = Base64.getUrlEncoder().withoutPadding()

    /** Runs the command named in [invocation], whose options the parser has already checked. */
    suspend fun run(
        invocation: Invocation,
        session: Session,
    ): Int {
        val group = invocation.group
        return when (invocation.command) {
            "group-create" -> create(session, checkNotNull(invocation.device))
            "group-add" -> add(session, checkNotNull(group), checkNotNull(invocation.device))
            "group-remove" -> remove(session, checkNotNull(group), checkNotNull(invocation.device))
            "group-post" -> post(session, checkNotNull(group), checkNotNull(invocation.text))
            "group-fetch" -> fetch(session, checkNotNull(group), invocation.seq ?: 0L)
            "group-status" -> status(session, checkNotNull(group))
            "group-ack" -> ack(session, checkNotNull(group), checkNotNull(invocation.seq))
            "group-leave" -> leave(session, checkNotNull(group))
            "welcomes" -> welcomes(session)
            else -> ackWelcome(session, checkNotNull(invocation.welcome))
        }
    }

    /** Creates a group with a fresh random id and a first commit that adds [device]. */
    suspend fun create(
        session: Session,
        device: String,
    ): Int {
        val groupId = random.nextBytes(GROUP_ID_BYTES)
        val commit = commit(0, added = listOf(device))
        val posted = value(api.createGroup(session, groupId, commit))
        out.println("created group ${base64.encodeToString(groupId)} at seq ${posted.seq}, epoch ${posted.epoch}")
        return EXIT_OK
    }

    /** A commit that adds [device] to the group, at the epoch the server reports now. */
    suspend fun add(
        session: Session,
        group: ByteArray,
        device: String,
    ): Int = change(session, group) { epoch -> commit(epoch, added = listOf(device)) }

    /** A commit that removes [device] (a member or a ghost) from the group. */
    suspend fun remove(
        session: Session,
        group: ByteArray,
        device: String,
    ): Int = change(session, group) { epoch -> commit(epoch, removed = listOf(device)) }

    suspend fun post(
        session: Session,
        group: ByteArray,
        text: String,
    ): Int {
        val envelope =
            DeliveryEnvelope(
                random.nextBytes(MSG_ID_BYTES),
                MessageKind.APPLICATION,
                null,
                emptyList(),
                emptyList(),
                emptyMap(),
                text.encodeToByteArray(),
            )
        val posted = value(api.postMessage(session, group, envelope))
        out.println("posted at seq ${posted.seq}")
        return EXIT_OK
    }

    suspend fun fetch(
        session: Session,
        group: ByteArray,
        after: Long,
    ): Int {
        val page = value(api.fetchMessages(session, group, after))
        page.messages.forEach { message ->
            val sender = message.senderDeviceId ?: "gone"
            val detail =
                when (message.kind) {
                    MessageKind.COMMIT -> "epoch ${message.epoch} added ${message.added.size} removed ${message.removed.size}"
                    MessageKind.APPLICATION -> "text \"${message.payload.decodeToString()}\""
                }
            out.println("message ${message.seq} ${message.kind.name.lowercase()} from $sender: $detail")
        }
        out.println("retained ${page.oldestSeq}..${page.lastSeq}")
        return EXIT_OK
    }

    suspend fun status(
        session: Session,
        group: ByteArray,
    ): Int {
        val state = value(api.groupStatus(session, group))
        val sent = state.lastSentSeq?.let { "seq $it id ${state.lastClientMsgId?.let(base64::encodeToString)}" } ?: "nothing"
        out.println("epoch ${state.epoch}, retained ${state.oldestSeq}..${state.lastSeq}, acknowledged ${state.ackedSeq}, last sent $sent")
        return EXIT_OK
    }

    suspend fun ack(
        session: Session,
        group: ByteArray,
        seq: Long,
    ): Int {
        value(api.ackMessages(session, group, seq))
        out.println("acknowledged up to $seq")
        return EXIT_OK
    }

    suspend fun leave(
        session: Session,
        group: ByteArray,
    ): Int {
        value(api.leaveGroup(session, group))
        out.println("left the group")
        return EXIT_OK
    }

    suspend fun welcomes(session: Session): Int {
        val pending = value(api.pendingWelcomes(session))
        if (pending.isEmpty()) out.println("no pending welcomes")
        pending.forEach { out.println("welcome ${it.id} group ${base64.encodeToString(it.groupId)} (${it.welcome.size} bytes)") }
        return EXIT_OK
    }

    suspend fun ackWelcome(
        session: Session,
        id: String,
    ): Int {
        value(api.ackWelcome(session, id))
        out.println("welcome acknowledged")
        return EXIT_OK
    }

    private suspend fun change(
        session: Session,
        group: ByteArray,
        build: (Long) -> DeliveryEnvelope,
    ): Int {
        val state = value(api.groupStatus(session, group))
        val posted = value(api.postMessage(session, group, build(state.epoch)))
        out.println("committed at seq ${posted.seq}, epoch ${posted.epoch}")
        return EXIT_OK
    }

    private fun commit(
        epoch: Long,
        added: List<String> = emptyList(),
        removed: List<String> = emptyList(),
    ) = DeliveryEnvelope(
        clientMsgId = random.nextBytes(MSG_ID_BYTES),
        kind = MessageKind.COMMIT,
        epoch = epoch,
        added = added,
        removed = removed,
        welcomes = added.associateWith { random.nextBytes(TEST_BYTES) },
        payload = random.nextBytes(TEST_BYTES),
    )

    private fun <T> value(result: ApiResult<T>): T =
        when (result) {
            is ApiResult.Ok -> result.value
            is ApiResult.Failed -> fail(result.error)
        }

    private companion object {
        const val GROUP_ID_BYTES = 16
        const val MSG_ID_BYTES = 16
        const val TEST_BYTES = 64
    }
}
