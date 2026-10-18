package app.gifitup.cli

import app.gifitup.shared.data.storage.IdentityStore
import app.gifitup.shared.data.storage.SnapshotKeyStore
import app.gifitup.shared.data.storage.StoredIdentity
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.Base64

/**
 * The CLI's state dir: one identity, its snapshot key and a lock file. **Unprotected by design** (CLAUDE.md "Dev CLI"):
 * the key sits in a plain file next to the snapshot, so a copy of the dir is the account. Test accounts only.
 *
 * - Files and the dir are owner-only where the file system supports POSIX permissions; best effort elsewhere (Windows).
 * - Writes are atomic: a temp file in the same dir, flushed, then moved over the target, so a crash leaves the old or
 *   the new file, never a torn one ([IdentityStore.save]'s contract).
 * - [lock] is held for a whole command, so two CLI processes can't use one dir at once (#95 SEC-1, SEC-7): the use
 *   cases serialize calls within one process only.
 */
class StateDir(
    private val dir: Path,
) {
    private val identityFile = dir.resolve("identity")
    private val keyFile = dir.resolve("snapshot-key")

    /** Creates the dir if needed and takes the lock; close it when the command ends. */
    fun lock(): AutoCloseable {
        createOwnerOnlyDir()
        val lockFile = dir.resolve("lock")
        val options = setOf(StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        val channel =
            if (posix) {
                FileChannel.open(lockFile, options, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
            } else {
                FileChannel.open(lockFile, options)
            }
        val lock: FileLock? =
            try {
                channel.tryLock()
            } catch (ignored: OverlappingFileLockException) {
                null
            }
        if (lock == null) {
            channel.close()
            throw CliError("another gifitup-cli is using this state dir", EXIT_REFUSED)
        }
        return AutoCloseable {
            lock.release()
            channel.close()
        }
    }

    val identities: IdentityStore =
        object : IdentityStore {
            override suspend fun load(): StoredIdentity? = readOrNull(identityFile)?.let(::parseIdentity)

            override suspend fun save(identity: StoredIdentity) = writeAtomically(identityFile, formatIdentity(identity))

            override suspend fun clear() {
                Files.deleteIfExists(identityFile)
            }
        }

    val keys: SnapshotKeyStore =
        object : SnapshotKeyStore {
            override suspend fun load(): ByteArray? =
                readOrNull(keyFile)?.also {
                    if (it.size !=
                        KEY_BYTES
                    ) {
                        throw CliError("the state dir's snapshot key is damaged", EXIT_REFUSED)
                    }
                }

            override suspend fun save(key: ByteArray) = writeAtomically(keyFile, key)

            override suspend fun clear() {
                Files.deleteIfExists(keyFile)
            }
        }

    private fun readOrNull(file: Path): ByteArray? = if (Files.exists(file)) Files.readAllBytes(file) else null

    private fun createOwnerOnlyDir() {
        if (Files.isDirectory(dir)) return
        if (posix) {
            Files.createDirectories(dir, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        } else {
            Files.createDirectories(dir)
        }
    }

    private fun writeAtomically(
        target: Path,
        bytes: ByteArray,
    ) {
        createOwnerOnlyDir()
        val temp =
            if (posix) {
                Files.createTempFile(dir, ".tmp-", "", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
            } else {
                Files.createTempFile(dir, ".tmp-", "")
            }
        try {
            FileChannel.open(temp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use { channel ->
                channel.write(ByteBuffer.wrap(bytes))
                channel.force(true)
            }
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    private val posix: Boolean get() = "posix" in dir.fileSystem.supportedFileAttributeViews()

    internal companion object {
        const val KEY_BYTES = 32
        private val base64 = Base64.getEncoder()
        private val base64Decoder = Base64.getDecoder()

        /** One `key=value` per line; the snapshot is base64. The invite code is never part of it (#79 SEC-5). */
        fun formatIdentity(identity: StoredIdentity): ByteArray =
            buildString {
                appendLine("origin=${identity.origin}")
                appendLine("username=${identity.username}")
                appendLine("deviceId=${identity.deviceId.orEmpty()}")
                appendLine("snapshot=${base64.encodeToString(identity.snapshot)}")
            }.encodeToByteArray()

        private fun damaged(): Nothing = throw CliError("the state dir's identity file is damaged", EXIT_REFUSED)

        fun parseIdentity(bytes: ByteArray): StoredIdentity {
            val fields =
                bytes
                    .decodeToString()
                    .lines()
                    .filter { it.isNotEmpty() }
                    .associate { it.substringBefore('=') to it.substringAfter('=', missingDelimiterValue = "") }
            val snapshot =
                fields["snapshot"]?.let { runCatching { base64Decoder.decode(it) }.getOrNull() } ?: damaged()
            return StoredIdentity(
                origin = fields["origin"]?.takeIf { it.isNotEmpty() } ?: damaged(),
                username = fields["username"]?.takeIf { it.isNotEmpty() } ?: damaged(),
                snapshot = snapshot,
                deviceId = fields["deviceId"]?.takeIf { it.isNotEmpty() },
            )
        }
    }
}
