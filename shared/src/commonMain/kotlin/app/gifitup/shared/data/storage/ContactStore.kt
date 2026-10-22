package app.gifitup.shared.data.storage

import app.gifitup.shared.data.contacts.Contact
import app.gifitup.shared.data.contacts.ContactBook
import app.gifitup.shared.data.contacts.ContactStatus
import app.gifitup.shared.data.contacts.KeyBytes
import app.gifitup.shared.data.contacts.Observation
import app.gifitup.shared.data.contacts.TrustConflict

/** What a store found: the book, or nothing usable (ADR-0014). Never "empty because it was missing or damaged". */
sealed interface ContactLoad {
    class Book(
        val book: ContactBook,
    ) : ContactLoad

    /** No record, a damaged or oversized one, one of another identity, or no identity at all: claims are refused. */
    data object Unavailable : ContactLoad
}

/**
 * The persisted [ContactBook]. A save is atomic and durable when it returns, and throws when it can't be (the caller
 * then treats the claim as blocked). The record is created empty together with the identity ([IdentityStore.save]),
 * so a missing record next to an identity is [ContactLoad.Unavailable], not a fresh book.
 */
interface ContactStore {
    suspend fun load(): ContactLoad

    suspend fun save(book: ContactBook)
}

/** [ContactStore] over the app's [LocalStore] under one versioned key, bound to the stored identity. */
class RecordContactStore(
    private val store: LocalStore,
    private val identities: IdentityStore,
) : ContactStore {
    override suspend fun load(): ContactLoad {
        val identity = identities.load()
        val bytes = if (identity == null) null else store.get(KEY)
        val book = if (identity == null || bytes == null) null else ContactRecordCodec.decode(bytes, identity.origin, identity.username)
        return if (book == null) ContactLoad.Unavailable else ContactLoad.Book(book)
    }

    override suspend fun save(book: ContactBook) {
        val identity = identities.load() ?: throw LocalStateException(LocalStateException.Problem.DAMAGED)
        store.putAll(mapOf(KEY to ContactRecordCodec.encode(book, identity.origin, identity.username)))
    }

    internal companion object {
        const val KEY = "contacts"
    }
}

/**
 * `version || text(origin) || text(username) || u16 count || contact*`, strict like the identity record: an unknown
 * version, a short or long field, a non-canonical value, a count over the caps or trailing bytes make the whole record
 * unreadable ([decode] returns null), never a partial book. The origin and username are the identity's: a record that
 * belongs to another identity is unreadable.
 */
object ContactRecordCodec {
    private const val VERSION: Byte = 1
    const val MAX_BYTES: Int = 1024 * 1024
    private const val MAX_CONTACTS = ContactBook.MAX_PINS + ContactBook.MAX_UNCONFIRMED
    private const val MAX_DEVICES = 10
    private const val KEY_BYTES = 32
    private val UUID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
    private val USERNAME = Regex("^[a-z0-9_]{3,32}$")

    fun empty(
        origin: String,
        username: String,
    ): ByteArray = encode(ContactBook(), origin, username)

    fun encode(
        book: ContactBook,
        origin: String,
        username: String,
    ): ByteArray {
        require(book.contacts.size <= MAX_CONTACTS) { "too many contacts" }
        val out = Writer()
        out.byte(VERSION.toInt())
        out.text(origin)
        out.text(username)
        out.u16(book.contacts.size)
        book.contacts.values
            .sortedBy { it.accountId }
            .forEach { out.contact(it) }
        return out.toBytes().also { check(it.size <= MAX_BYTES) { "record too large" } }
    }

    fun decode(
        bytes: ByteArray,
        origin: String,
        username: String,
    ): ContactBook? = if (bytes.size > MAX_BYTES) null else runCatching { read(bytes, origin, username) }.getOrNull()

    /** Throws on anything wrong; [decode] turns every throw into "unreadable". */
    private fun read(
        bytes: ByteArray,
        origin: String,
        username: String,
    ): ContactBook {
        val reader = Reader(bytes)
        require(reader.byte() == VERSION.toInt() && reader.text() == origin && reader.text() == username) { "not this identity's record" }
        val count = reader.u16()
        require(count <= MAX_CONTACTS) { "too many contacts" }
        val contacts = (0 until count).map { reader.contact() }
        require(reader.atEnd() && contacts.map { it.accountId }.toSet().size == count) { "trailing bytes or duplicate account" }
        return ContactBook(contacts.associateBy { it.accountId }).also { require(it.pinCount <= ContactBook.MAX_PINS) { "too many pins" } }
    }

    private fun Writer.contact(c: Contact) {
        text(c.accountId)
        text(c.username)
        byte(c.status.ordinal)
        long(c.firstSeen)
        long(c.lastSeen)
        devices(c.devices)
        val pending = c.pending
        byte(if (pending == null) 0 else 1)
        if (pending != null) {
            byte(pending.reason.ordinal)
            text(pending.username)
            devices(pending.devices)
        }
    }

    private fun Writer.devices(devices: Map<KeyBytes, String>) {
        require(devices.size <= MAX_DEVICES) { "too many devices" }
        byte(devices.size)
        devices.forEach { (key, id) ->
            bytes(key.bytes)
            text(id)
        }
    }

    private fun Reader.contact(): Contact {
        val accountId = text().also { require(UUID.matches(it)) }
        val username = text().also { require(USERNAME.matches(it)) }
        val status = ContactStatus.entries[byte()]
        val firstSeen = long()
        val lastSeen = long()
        val devices = devices()
        val pending =
            when (byte()) {
                0 -> null
                1 -> Observation(TrustConflict.entries[byte()], text().also { require(USERNAME.matches(it)) }, devices())
                else -> error("bad flag")
            }
        require((status == ContactStatus.BLOCKED) == (pending != null)) { "blocked iff pending" }
        return Contact(accountId, username, status, devices, pending, firstSeen, lastSeen)
    }

    private fun Reader.devices(): Map<KeyBytes, String> {
        val count = byte()
        require(count <= MAX_DEVICES)
        val devices = (0 until count).associate { KeyBytes(bytes(KEY_BYTES)) to text().also { id -> require(UUID.matches(id)) } }
        require(devices.size == count) { "duplicate key" }
        return devices
    }

    private class Writer {
        private val out = ArrayList<Byte>()

        fun byte(value: Int) {
            out += value.toByte()
        }

        fun u16(value: Int) {
            byte(value shr Byte.SIZE_BITS)
            byte(value and BYTE_MASK)
        }

        fun long(value: Long) {
            for (shift in LONG_SHIFTS) byte((value shr shift).toInt() and BYTE_MASK)
        }

        fun bytes(value: ByteArray) {
            out.addAll(value.toList())
        }

        fun text(value: String) {
            val encoded = value.encodeToByteArray()
            require(encoded.isNotEmpty() && encoded.size <= MAX_TEXT) { "text out of range" }
            u16(encoded.size)
            bytes(encoded)
        }

        fun toBytes(): ByteArray = out.toByteArray()
    }

    private class Reader(
        private val data: ByteArray,
    ) {
        private var at = 0

        fun atEnd() = at == data.size

        fun byte(): Int = (if (at < data.size) data[at++].toInt() else error("short")) and BYTE_MASK

        fun u16(): Int = (byte() shl Byte.SIZE_BITS) or byte()

        fun long(): Long = LONG_SHIFTS.fold(0L) { acc, _ -> (acc shl Byte.SIZE_BITS) or byte().toLong() }

        fun bytes(count: Int): ByteArray {
            require(at + count <= data.size) { "short" }
            return data.copyOfRange(at, at + count).also { at += count }
        }

        fun text(): String {
            val length = u16()
            require(length in 1..MAX_TEXT)
            return bytes(length).decodeToString(throwOnInvalidSequence = true)
        }
    }

    private const val MAX_TEXT = 0xFFFF
    private const val BYTE_MASK = 0xFF
    private val LONG_SHIFTS = (Long.SIZE_BITS - Byte.SIZE_BITS downTo 0 step Byte.SIZE_BITS).toList()
}
