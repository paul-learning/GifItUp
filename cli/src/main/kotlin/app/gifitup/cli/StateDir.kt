package app.gifitup.cli

import app.gifitup.shared.data.storage.ContactRecordCodec
import app.gifitup.shared.data.storage.IdentityStore
import app.gifitup.shared.data.storage.SnapshotKeyStore
import app.gifitup.shared.data.storage.StoredIdentity
import app.gifitup.shared.domain.contacts.ContactBook
import app.gifitup.shared.domain.contacts.ContactLoad
import app.gifitup.shared.domain.contacts.ContactStore
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
 * The CLI's state dir: one identity, its snapshot key and a lock file. **Unprotected by design** (AGENTS.md "Dev CLI"):
 * the key sits in a plain file next to the snapshot, so a copy of the dir is the account. Test accounts only.
 *
 * - Files are created owner-only (`rw-------`) where the file system supports POSIX permissions, and so is the dir
 *   if the CLI creates it; an existing dir keeps its permissions. On Windows nothing is set: the files inherit the
 *   folder's ACL, so the dir belongs under the user's profile (#102 SEC-17).
 * - Writes are atomic: a temp file in the same dir, flushed, then moved over the target, so a crash leaves the old or
 *   the new file, never a torn one; on POSIX the dir is synced after every move and delete, so a returned save or
 *   clear is durable ([IdentityStore.save]'s contract).
 * - [lock] is held for a whole command, so two CLI processes can't use one dir at once (#95 SEC-1, SEC-7): the use
 *   cases serialize calls within one process only.
 */
class StateDir(
    private val dir: Path,
) {
    private val identityFile = dir.resolve("identity")
    private val keyFile = dir.resolve("snapshot-key")
    private val contactsFile = dir.resolve("contacts")

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

            /** Like the app's store (ADR-0014): the first write of an identity creates the empty book; later saves leave it. */
            override suspend fun save(identity: StoredIdentity) {
                if (!Files.exists(identityFile)) {
                    writeAtomically(contactsFile, ContactRecordCodec.empty(identity.origin, identity.username))
                }
                writeAtomically(identityFile, formatIdentity(identity))
            }

            override suspend fun clear() {
                delete(identityFile)
                delete(contactsFile)
            }
        }

    /** The contact book (ADR-0014): one file, bound to the identity, unprotected like the rest of the dir. */
    val contacts: ContactStore =
        object : ContactStore {
            override suspend fun load(): ContactLoad {
                val identity = identities.load()
                val bytes = readOrNull(contactsFile)
                val book =
                    if (identity == null ||
                        bytes == null
                    ) {
                        null
                    } else {
                        ContactRecordCodec.decode(bytes, identity.origin, identity.username)
                    }
                return if (book == null) ContactLoad.Unavailable else ContactLoad.Book(book)
            }

            override suspend fun save(book: ContactBook) {
                val identity = identities.load() ?: throw CliError("the state dir holds no identity", EXIT_REFUSED)
                writeAtomically(contactsFile, ContactRecordCodec.encode(book, identity.origin, identity.username))
            }
        }

    val keys: SnapshotKeyStore =
        object : SnapshotKeyStore {
            override suspend fun load(): ByteArray? = readOrNull(keyFile)?.also(::checkKey)

            override suspend fun save(key: ByteArray) = writeAtomically(keyFile, checkKey(key))

            override suspend fun clear() = delete(keyFile)
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
            syncDir()
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    private fun delete(file: Path) {
        if (Files.deleteIfExists(file)) syncDir()
    }

    /**
     * Makes a rename or delete in the dir durable, which the stores promise when they return (#102 SEC-15). POSIX only:
     * on Windows a directory can't be opened as a channel, and the move/delete is all there is.
     */
    private fun syncDir() {
        if (posix) FileChannel.open(dir, StandardOpenOption.READ).use { it.force(true) }
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

        /** [key] if it has [KEY_BYTES] bytes, so a damaged key is refused on load and never written (#102 CR-3). */
        fun checkKey(key: ByteArray): ByteArray {
            if (key.size != KEY_BYTES) throw CliError("the state dir's snapshot key is damaged", EXIT_REFUSED)
            return key
        }

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
