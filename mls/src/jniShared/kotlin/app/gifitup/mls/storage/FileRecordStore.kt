package app.gifitup.mls.storage

import app.gifitup.shared.data.storage.RecordStore
import app.gifitup.shared.data.storage.RecordStoreException
import app.gifitup.shared.data.storage.isRecordName
import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * A [RecordStore] on the filesystem (Android and JVM): `root/<folder>/<name>`, both checked with [isRecordName]
 * before touching the disk, so no name can leave `root` (SEC-9).
 *
 * Every write is atomic for its one record: a temp file in the same folder, fsync, atomic rename over the target,
 * fsync the folder; the temp file is deleted whatever happens. A failed `put` leaves the old record.
 *
 * One instance per directory; calls are serialized with a mutex. Blocking I/O runs on [Dispatchers.IO].
 * Every failure is a [RecordStoreException] with no message and no cause (security rule 4).
 */
class FileRecordStore(
    private val root: File,
    private val moveIntoPlace: (from: Path, to: Path) -> Unit = ::atomicMove,
    private val syncDirectory: (dir: Path) -> Unit = ::forceDirectory,
) : RecordStore {
    private val mutex = Mutex()

    override suspend fun put(
        folder: String,
        name: String,
        bytes: ByteArray,
    ) = withContext(Dispatchers.IO) {
        mutex.withLock {
            check(folder, name)
            val folderPath = folderPath(folder)
            val target = folderPath.resolve(name).toFile()
            val temp = File(folderPath.toFile(), ".tmp-${java.util.UUID.randomUUID()}")
            try {
                folderPath.createDirectories()
                Files.write(
                    temp.toPath(),
                    bytes,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                )
                FileChannel.open(temp.toPath(), StandardOpenOption.WRITE).use { it.force(true) }
                moveIntoPlace(temp.toPath(), target.toPath())
                syncDirectory(folderPath)
            } catch (_: IOException) {
                throw RecordStoreException()
            } catch (_: SecurityException) {
                throw RecordStoreException()
            } finally {
                temp.delete()
            }
        }
    }

    override suspend fun get(
        folder: String,
        name: String,
    ): ByteArray? = withContext(Dispatchers.IO) {
        mutex.withLock {
            check(folder, name)
            val path = folderPath(folder).resolve(name)
            try {
                if (!path.exists()) return@withContext null
                Files.readAllBytes(path)
            } catch (_: IOException) {
                throw RecordStoreException()
            } catch (_: SecurityException) {
                throw RecordStoreException()
            }
        }
    }

    override suspend fun list(folder: String): List<String> = withContext(Dispatchers.IO) {
        mutex.withLock {
            check(folder)
            val folderPath = folderPath(folder)
            try {
                if (!folderPath.exists()) return@withContext emptyList()
                val result = mutableListOf<String>()
                val dir = folderPath.toFile()
                for (file in dir.listFiles().orEmpty()) {
                    if (file.isFile && isRecordName(file.name) && !file.name.startsWith(".tmp-")) {
                        result.add(file.name)
                    } else if (file.isFile && file.name.startsWith(".tmp-")) {
                        file.delete()
                    }
                }
                result
            } catch (_: IOException) {
                throw RecordStoreException()
            } catch (_: SecurityException) {
                throw RecordStoreException()
            }
        }
    }

    override suspend fun delete(
        folder: String,
        name: String,
    ) = withContext(Dispatchers.IO) {
        mutex.withLock {
            check(folder, name)
            try {
                val path = folderPath(folder).resolve(name)
                if (path.exists()) {
                    Files.delete(path)
                    syncDirectory(folderPath(folder))
                }
            } catch (_: IOException) {
                throw RecordStoreException()
            } catch (_: SecurityException) {
                throw RecordStoreException()
            }
        }
    }

    override suspend fun deleteFolder(folder: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            check(folder)
            try {
                val folderPath = folderPath(folder)
                if (folderPath.exists()) {
                    val dir = folderPath.toFile()
                    for (file in dir.listFiles().orEmpty()) {
                        if (file.isFile) {
                            file.delete()
                        }
                    }
                    dir.delete()
                    syncDirectory(root.toPath())
                }
            } catch (_: IOException) {
                throw RecordStoreException()
            } catch (_: SecurityException) {
                throw RecordStoreException()
            }
        }
    }

    override suspend fun folders(): List<String> = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                val result = mutableListOf<String>()
                val rootDir = root
                if (rootDir.exists() && rootDir.isDirectory) {
                    for (file in rootDir.listFiles().orEmpty()) {
                        if (file.isDirectory && isRecordName(file.name)) {
                            result.add(file.name)
                        }
                    }
                }
                result
            } catch (_: IOException) {
                throw RecordStoreException()
            } catch (_: SecurityException) {
                throw RecordStoreException()
            }
        }
    }

    override suspend fun isEmpty(): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                if (!root.exists()) return@withContext true
                val dir = root
                if (!dir.isDirectory) return@withContext false
                for (entry in dir.listFiles().orEmpty()) {
                    return@withContext false
                }
                true
            } catch (_: IOException) {
                throw RecordStoreException()
            } catch (_: SecurityException) {
                throw RecordStoreException()
            }
        }
    }

    override suspend fun deleteAll() = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                if (root.exists()) {
                    val dir = root
                    for (entry in dir.listFiles().orEmpty()) {
                        if (entry.isDirectory) {
                            for (file in entry.listFiles().orEmpty()) {
                                file.delete()
                            }
                            entry.delete()
                        } else {
                            entry.delete()
                        }
                    }
                    syncDirectory(root.toPath())
                }
            } catch (_: IOException) {
                throw RecordStoreException()
            } catch (_: SecurityException) {
                throw RecordStoreException()
            }
        }
    }

    private fun check(
        folder: String,
        name: String? = null,
    ) {
        if (!isRecordName(folder) || (name != null && !isRecordName(name))) {
            throw RecordStoreException()
        }
    }

    private fun folderPath(folder: String): Path = root.toPath().resolve(folder)

    companion object {
        internal fun atomicMove(
            from: Path,
            to: Path,
        ) {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }

        internal fun forceDirectory(dir: Path) = FileChannel.open(dir, StandardOpenOption.READ).use { it.force(true) }
    }
}
