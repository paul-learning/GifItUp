package app.gifitup.mls.storage

import app.gifitup.shared.data.storage.RecordStore
import app.gifitup.shared.data.storage.RecordStoreException
import app.gifitup.shared.data.storage.isRecordName
import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.DirectoryIteratorException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID

/**
 * The [RecordStore] on the filesystem (Android and JVM, ADR-0017): `root/<folder>/<name>`, both checked with
 * [isRecordName] **before** the disk is touched, so no name can leave `root` (SEC-9). On Android the app passes a
 * directory under `noBackupFilesDir`, so the records are never part of a backup.
 *
 * Every write is atomic for its one record: the bytes go to a temp file in the same folder, the file is synced, renamed
 * over the target, and the folder is synced; the temp file is deleted whatever happens, so a failed `put` leaves the
 * old record. A listing deletes the temp files a crash left. Nothing here deletes what it did not make, except by the
 * explicit [deleteFolder] and [deleteAll].
 *
 * [isEmpty] is `true` only if `root` holds **no entry at all** (a malformed name, a foreign file, a temp file or a
 * stray folder all count); every listing goes through calls that throw on an I/O error, so an unreadable directory is
 * never "empty". Every failure is a [RecordStoreException] with no message and no cause (security rule 4).
 *
 * One instance per directory; the calls are serialized here and block, so callers keep them off the main thread (as
 * with `FileLocalStore`).
 */
@Suppress("TooManyFunctions") // The port's seven calls plus the helpers that make them throw instead of misreading.
class FileRecordStore internal constructor(
    private val root: File,
    private val moveIntoPlace: (from: Path, to: Path) -> Unit,
    private val syncDirectory: (dir: Path) -> Unit,
) : RecordStore {
    constructor(root: File) : this(root, { from, to -> atomicMove(from, to) }, { dir -> forceDirectory(dir) })

    private val lock = Any()
    private val rootPath: Path = root.toPath()

    override suspend fun put(
        folder: String,
        name: String,
        bytes: ByteArray,
    ) = synchronized(lock) {
        validate(folder, name)
        io {
            val folderPath = rootPath.resolve(folder)
            val newFolder = !Files.isDirectory(folderPath)
            Files.createDirectories(folderPath)
            val temp = folderPath.resolve("$TEMP_PREFIX${UUID.randomUUID()}")
            try {
                Files.write(temp, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
                FileChannel.open(temp, StandardOpenOption.WRITE).use { it.force(true) }
                moveIntoPlace(temp, folderPath.resolve(name))
                syncDirectory(folderPath)
                if (newFolder) syncDirectory(rootPath)
            } finally {
                Files.deleteIfExists(temp)
            }
        }
    }

    override suspend fun get(
        folder: String,
        name: String,
    ): ByteArray? =
        synchronized(lock) {
            validate(folder, name)
            io {
                try {
                    Files.readAllBytes(rootPath.resolve(folder).resolve(name))
                } catch (_: NoSuchFileException) {
                    null
                }
            }
        }

    override suspend fun list(folder: String): List<String> =
        synchronized(lock) {
            validate(folder)
            io {
                val names = mutableListOf<String>()
                for (entry in entries(rootPath.resolve(folder))) {
                    val file = entry.fileName.toString()
                    when {
                        file.startsWith(TEMP_PREFIX) -> runCatching { Files.deleteIfExists(entry) }
                        isRecordName(file) && Files.isRegularFile(entry) -> names += file
                    }
                }
                names
            }
        }

    override suspend fun delete(
        folder: String,
        name: String,
    ) = synchronized(lock) {
        validate(folder, name)
        io {
            val folderPath = rootPath.resolve(folder)
            if (Files.deleteIfExists(folderPath.resolve(name))) syncDirectory(folderPath)
        }
    }

    override suspend fun deleteFolder(folder: String) =
        synchronized(lock) {
            validate(folder)
            io {
                val folderPath = rootPath.resolve(folder)
                if (Files.exists(folderPath)) {
                    entries(folderPath).forEach { Files.delete(it) }
                    Files.delete(folderPath)
                    syncDirectory(rootPath)
                }
            }
        }

    override suspend fun folders(): List<String> =
        synchronized(lock) {
            io { entries(rootPath).filter { isRecordName(it.fileName.toString()) && Files.isDirectory(it) }.map { it.fileName.toString() } }
        }

    override suspend fun isEmpty(): Boolean =
        synchronized(lock) {
            io { !Files.exists(rootPath) || (Files.isDirectory(rootPath) && entries(rootPath).isEmpty()) }
        }

    override suspend fun deleteAll() =
        synchronized(lock) {
            io {
                if (Files.exists(rootPath)) {
                    for (entry in entries(rootPath)) {
                        if (Files.isDirectory(entry)) entries(entry).forEach { Files.delete(it) }
                        Files.delete(entry)
                    }
                    syncDirectory(rootPath)
                }
            }
        }

    /** The entries of [dir] (none if it does not exist); an I/O error is thrown, never read as "empty". */
    private fun entries(dir: Path): List<Path> =
        try {
            Files.newDirectoryStream(dir).use { it.toList() }
        } catch (_: NoSuchFileException) {
            emptyList()
        }

    private fun validate(
        folder: String,
        name: String? = null,
    ) {
        if (!isRecordName(folder) || (name != null && !isRecordName(name))) throw RecordStoreException()
    }

    /** Runs a disk operation; whatever the disk or the platform throws becomes the closed [RecordStoreException]. */
    private inline fun <T> io(block: () -> T): T =
        try {
            block()
        } catch (_: IOException) {
            throw RecordStoreException()
        } catch (_: SecurityException) {
            throw RecordStoreException()
        } catch (_: DirectoryIteratorException) {
            throw RecordStoreException()
        }

    internal companion object {
        const val TEMP_PREFIX = ".tmp-"

        fun atomicMove(
            from: Path,
            to: Path,
        ) {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }

        fun forceDirectory(dir: Path) = FileChannel.open(dir, StandardOpenOption.READ).use { it.force(true) }
    }
}
