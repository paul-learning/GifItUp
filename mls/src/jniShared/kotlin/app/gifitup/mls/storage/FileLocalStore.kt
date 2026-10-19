package app.gifitup.mls.storage

import app.gifitup.shared.data.storage.LocalStateException
import app.gifitup.shared.data.storage.LocalStateException.Problem.DAMAGED
import app.gifitup.shared.data.storage.LocalStateException.Problem.STORAGE
import app.gifitup.shared.data.storage.LocalStore
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * The Android [LocalStore] (ADR-0011): all entries in one file in an app-private directory (`noBackupFilesDir`, so it
 * is never part of a backup). Every [putAll] writes the complete new content to a temp file, syncs it, renames it over
 * the old file and syncs the directory, so after a crash the file is the old content or the new one, never a mix, and
 * the store keeps no copy of the old one. The temp file is deleted whatever happens.
 *
 * One instance per device: the app builds it once per process, and the calls are serialized here. The calls block, so
 * callers keep them off the main thread. A file that isn't ours (wrong version, short, duplicate keys, trailing bytes,
 * too large) is `DAMAGED`; I/O failures are `STORAGE`.
 */
class FileLocalStore internal constructor(
    private val dir: File,
    private val moveIntoPlace: (from: Path, to: Path) -> Unit,
) : LocalStore {
    constructor(dir: File) : this(dir, ::atomicMove)

    private val file = File(dir, FILE_NAME)
    private val temp = File(dir, TEMP_NAME)
    private val lock = Any()

    override suspend fun get(key: String): ByteArray? = synchronized(lock) { read()[key]?.copyOf() }

    override suspend fun putAll(entries: Map<String, ByteArray?>) =
        synchronized(lock) {
            val merged = read().toMutableMap()
            entries.forEach { (key, value) -> if (value == null) merged.remove(key) else merged[key] = value.copyOf() }
            val content = encode(merged)
            // The read side refuses a larger file as DAMAGED, so a write that big would destroy the identity (#113 SEC-1).
            if (content.size > MAX_FILE_BYTES) throw LocalStateException(STORAGE)
            write(content)
        }

    /** Deletes the file and any temp file left by a crash; for the wipe. Missing files are no error. */
    fun deleteAll() =
        synchronized(lock) {
            mapIo {
                val deleted = Files.deleteIfExists(file.toPath()) or Files.deleteIfExists(temp.toPath())
                if (deleted) syncDir()
            }
        }

    private fun read(): Map<String, ByteArray> =
        mapIo {
            if (!file.exists()) return@mapIo emptyMap()
            if (file.length() > MAX_FILE_BYTES) throw LocalStateException(DAMAGED)
            decode(Files.readAllBytes(file.toPath()))
        }

    private fun write(bytes: ByteArray) =
        mapIo {
            Files.createDirectories(dir.toPath())
            try {
                FileChannel
                    .open(
                        temp.toPath(),
                        StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE,
                        StandardOpenOption.TRUNCATE_EXISTING,
                    ).use {
                        it.write(ByteBuffer.wrap(bytes))
                        it.force(true)
                    }
                moveIntoPlace(temp.toPath(), file.toPath())
                syncDir()
            } finally {
                Files.deleteIfExists(temp.toPath())
            }
        }

    /** Makes a rename or delete durable when this returns (POSIX; Android is Linux). */
    private fun syncDir() = FileChannel.open(dir.toPath(), StandardOpenOption.READ).use { it.force(true) }

    private fun <T> mapIo(block: () -> T): T =
        try {
            block()
        } catch (e: LocalStateException) {
            throw e
        } catch (ignored: IOException) {
            throw LocalStateException(STORAGE)
        } catch (ignored: SecurityException) {
            throw LocalStateException(STORAGE)
        }

    internal companion object {
        const val FILE_NAME = "device-state"
        const val TEMP_NAME = "device-state.tmp"
        private const val VERSION: Byte = 1
        private const val MAX_FILE_BYTES = 16L * 1024 * 1024
        private const val MAX_KEY_BYTES = 0xFFFF

        /** `version(1) || count(4) || count × (keyLength(2) || key || valueLength(4) || value)`, keys sorted. */
        fun encode(entries: Map<String, ByteArray>): ByteArray {
            val out = ByteArrayOutputStream()
            DataOutputStream(out).apply {
                writeByte(VERSION.toInt())
                writeInt(entries.size)
                entries.toSortedMap().forEach { (key, value) ->
                    val keyBytes = key.encodeToByteArray()
                    require(keyBytes.isNotEmpty() && keyBytes.size <= MAX_KEY_BYTES) { "key out of range" }
                    writeShort(keyBytes.size)
                    write(keyBytes)
                    writeInt(value.size)
                    write(value)
                }
            }
            return out.toByteArray()
        }

        fun decode(bytes: ByteArray): Map<String, ByteArray> {
            val buffer = ByteBuffer.wrap(bytes)

            fun need(n: Int) {
                if (n < 0 || buffer.remaining() < n) damaged()
            }
            need(1 + Int.SIZE_BYTES)
            if (buffer.get() != VERSION) damaged()
            val count = buffer.getInt()
            val entries = LinkedHashMap<String, ByteArray>()
            repeat(count.coerceIn(0, bytes.size)) {
                need(Short.SIZE_BYTES)
                val keyLength = buffer.getShort().toInt() and MAX_KEY_BYTES
                need(keyLength)
                val key = bytes.decodeOrNull(buffer.position(), keyLength) ?: damaged()
                buffer.position(buffer.position() + keyLength)
                need(Int.SIZE_BYTES)
                val valueLength = buffer.getInt()
                need(valueLength)
                val value = ByteArray(valueLength).also { buffer.get(it) }
                if (key.isEmpty() || entries.put(key, value) != null) damaged()
            }
            if (entries.size != count || buffer.hasRemaining()) damaged()
            return entries
        }

        private fun atomicMove(
            from: Path,
            to: Path,
        ) {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }

        private fun damaged(): Nothing = throw LocalStateException(DAMAGED)

        private fun ByteArray.decodeOrNull(
            start: Int,
            length: Int,
        ): String? = runCatching { decodeToString(start, start + length, throwOnInvalidSequence = true) }.getOrNull()
    }
}
