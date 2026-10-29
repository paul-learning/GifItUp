package app.gifitup.shared.domain.messages

import app.gifitup.shared.data.content.GifReference
import app.gifitup.shared.domain.contacts.KeyBytes
import app.gifitup.shared.domain.groups.ByteReader
import app.gifitup.shared.domain.groups.ByteWriter

/** A message as the history keeps it: the message and the number that orders it among the group's arrivals. */
internal class StoredMessage(
    val message: ChatMessage,
    val arrival: Long,
)

/**
 * The plaintext inside a sealed history record (ADR-0017): a fixed binary layout, read strictly (every length is checked
 * against what is left, trailing bytes are refused) and only after the record's tag verified. A record that doesn't
 * read is `null`: the store counts it as unreadable and never shows or logs any of it.
 *
 * ```text
 * version(1) ‖ group id (u32 len) ‖ reference(32) ‖ arrival(i64) ‖ sender (flag, u32 len + UTF-8) ‖ seq (flag, i64)
 *   ‖ state(1) ‖ at(i64) ‖ body tag(1) ‖ body
 * body: 1 gif  = slug, url (u32 len each) ‖ width(u16) ‖ height(u16) ‖ caption (flag, u32 len + UTF-8)
 *       2 rename = name (u32 len + UTF-8);  3 unavailable;  4 update needed
 * ```
 */
internal object HistoryRecordCodec {
    private const val VERSION = 1
    const val REFERENCE_BYTES = 32
    private const val MAX_ID = 128
    private const val MAX_TEXT = 1024

    private const val SENDING_CODE = 1
    private const val SENT_CODE = 2
    private const val FAILED_CODE = 3

    private const val GIF = 1
    private const val RENAME = 2
    private const val UNAVAILABLE = 3
    private const val UPDATE_NEEDED = 4

    fun encode(stored: StoredMessage): ByteArray {
        val message = stored.message
        val out = ByteWriter()
        out.u8(VERSION)
        out.blob(message.groupId.bytes)
        out.raw(message.reference.bytes.also { check(it.size == REFERENCE_BYTES) })
        out.i64(stored.arrival)
        optionalText(out, message.sender)
        val seq = message.seq
        out.u8(if (seq == null) 0 else 1)
        if (seq != null) out.i64(seq)
        out.u8(stateCode(message.state))
        out.i64(message.at)
        writeBody(out, message.body)
        return out.toByteArray()
    }

    @Suppress("TooGenericExceptionCaught") // Whatever goes wrong while reading, the record is just unreadable.
    fun decode(bytes: ByteArray): StoredMessage? =
        try {
            read(ByteReader(bytes))
        } catch (_: Exception) {
            null
        }

    @Suppress("ReturnCount") // One early exit for each field that can be wrong.
    private fun read(input: ByteReader): StoredMessage? {
        if (input.u8() != VERSION) return null
        val group = KeyBytes(input.blob(MAX_ID))
        val reference = KeyBytes(input.bytes(REFERENCE_BYTES))
        val arrival = input.i64().takeIf { it >= 0 } ?: return null
        val sender = optionalText(input)
        val seq = optionalLong(input)
        val state = stateOf(input.u8())
        val at = input.i64()
        val body = readBody(input) ?: return null
        // A sender and a delivery state go together: ours have a state, received ones don't (and the reverse).
        if (!input.done() || (sender == null) != (state != null)) return null
        return StoredMessage(ChatMessage(group, reference, sender, seq, body, state, at), arrival)
    }

    private fun writeBody(
        out: ByteWriter,
        body: MessageBody,
    ) {
        when (body) {
            is MessageBody.Gif -> {
                out.u8(GIF)
                out.blob(body.reference.slug.encodeToByteArray())
                out.blob(body.reference.url.encodeToByteArray())
                out.u16(body.reference.width)
                out.u16(body.reference.height)
                optionalText(out, body.caption)
            }

            is MessageBody.GroupMeta -> {
                out.u8(RENAME)
                out.blob(body.name.encodeToByteArray())
            }

            MessageBody.Unavailable -> {
                out.u8(UNAVAILABLE)
            }

            MessageBody.UpdateNeeded -> {
                out.u8(UPDATE_NEEDED)
            }
        }
    }

    private fun readBody(input: ByteReader): MessageBody? =
        when (input.u8()) {
            GIF -> {
                val slug = input.blob(MAX_TEXT).decodeToString(throwOnInvalidSequence = true)
                val url = input.blob(MAX_TEXT).decodeToString(throwOnInvalidSequence = true)
                val width = input.u16()
                val height = input.u16()
                val caption = optionalText(input)
                // Checked again like anything that arrives: the stored bytes are ours, the rules are the codec's.
                GifReference.of(GifReference.PROVIDER, slug, url, width, height)?.let { MessageBody.Gif(it, caption) }
            }

            RENAME -> {
                MessageBody.GroupMeta(input.blob(MAX_TEXT).decodeToString(throwOnInvalidSequence = true))
            }

            UNAVAILABLE -> {
                MessageBody.Unavailable
            }

            UPDATE_NEEDED -> {
                MessageBody.UpdateNeeded
            }

            else -> {
                null
            }
        }

    /** The state's code on disk: 0 none, 1 sending, 2 sent, 3 failed; never the enum's ordinal. */
    private fun stateCode(state: DeliveryState?): Int =
        when (state) {
            null -> 0
            DeliveryState.SENDING -> SENDING_CODE
            DeliveryState.SENT -> SENT_CODE
            DeliveryState.FAILED -> FAILED_CODE
        }

    private fun stateOf(code: Int): DeliveryState? =
        when (code) {
            0 -> null
            SENDING_CODE -> DeliveryState.SENDING
            SENT_CODE -> DeliveryState.SENT
            FAILED_CODE -> DeliveryState.FAILED
            else -> invalidRecord()
        }

    private fun optionalLong(input: ByteReader): Long? =
        when (input.u8()) {
            0 -> null
            1 -> input.i64()
            else -> invalidRecord()
        }

    private fun optionalText(
        out: ByteWriter,
        text: String?,
    ) {
        out.u8(if (text == null) 0 else 1)
        if (text != null) out.blob(text.encodeToByteArray())
    }

    private fun optionalText(input: ByteReader): String? =
        when (input.u8()) {
            0 -> null
            1 -> input.blob(MAX_TEXT).decodeToString(throwOnInvalidSequence = true)
            else -> invalidRecord()
        }
}

private fun invalidRecord(): Nothing = throw IllegalArgumentException("unreadable record")
