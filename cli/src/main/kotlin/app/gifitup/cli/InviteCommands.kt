package app.gifitup.cli

import app.gifitup.shared.data.network.ApiError
import app.gifitup.shared.data.network.GifItUpApi
import app.gifitup.shared.data.network.Invite
import app.gifitup.shared.data.network.Session
import app.gifitup.shared.domain.auth.AuthError
import app.gifitup.shared.domain.auth.AuthResult
import app.gifitup.shared.domain.invite.CreateInvite
import app.gifitup.shared.domain.invite.ListInvites
import app.gifitup.shared.domain.invite.RevokeInvite
import java.io.IOException
import java.io.PrintStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant

/**
 * The invite commands (ADR-0010, #17), thin over the app's use cases. The invite code is a one-use credential: it is
 * never printed, only written to the `--link-file` the user named, as the link the app would show.
 */
internal class InviteCommands(
    private val api: GifItUpApi,
    private val out: PrintStream,
    private val report: suspend (AuthResult.Failed) -> Int,
) {
    /**
     * The link file is created (new, owner-only) before the invite, so a bad path costs no invite. If the invite isn't
     * created the empty file is removed; if the link can't be written the invite is revoked, best effort.
     */
    suspend fun create(
        session: Session,
        linkFile: Path,
    ): Int {
        val channel = LinkFile.createEmpty(linkFile)
        var written = false
        // Closed before the delete below: Windows can't delete an open file.
        return try {
            channel.use { createAndWrite(session, it, linkFile).also { written = true } }
        } finally {
            if (!written) Files.deleteIfExists(linkFile)
        }
    }

    /** Creates the invite and writes its link; returns only once the link is in the file (every failure throws). */
    private suspend fun createAndWrite(
        session: Session,
        channel: FileChannel,
        linkFile: Path,
    ): Int =
        when (val result = CreateInvite(api)(session)) {
            is AuthResult.Ok -> {
                val invite = result.value
                // Built before the write, so nothing can fail between the written link and a success.
                val line = "invite ${invite.inviteId} created, expires ${Instant.ofEpochSecond(invite.expiresAt)}; link in $linkFile"
                try {
                    LinkFile.write(channel, invite.link)
                } catch (e: IOException) {
                    val revoked = RevokeInvite(api)(session, invite.inviteId) is AuthResult.Ok
                    val after = if (revoked) "the invite was revoked" else "revoke it with invite-revoke --id ${invite.inviteId}"
                    throw CliError("couldn't write the link file (${e::class.java.simpleName}); $after", EXIT_REFUSED)
                }
                out.println(line)
                EXIT_OK
            }

            is AuthResult.Failed -> {
                report(result)
            }
        }

    suspend fun list(session: Session): Int =
        when (val result = ListInvites(api)(session)) {
            is AuthResult.Ok -> {
                result.value.forEach { out.println(describe(it)) }
                EXIT_OK
            }

            is AuthResult.Failed -> {
                report(result)
            }
        }

    /** Only an open invite of this account can be revoked; the server answers everything else with one `not_found`. */
    suspend fun revoke(
        session: Session,
        inviteId: String,
    ): Int =
        when (val result = RevokeInvite(api)(session, inviteId)) {
            is AuthResult.Ok -> {
                out.println("invite $inviteId revoked")
                EXIT_OK
            }

            is AuthResult.Failed -> {
                if (result.error == AuthError.Api(ApiError.NOT_FOUND)) {
                    throw CliError("no open invite of this account with that id (used, expired, revoked or unknown)", EXIT_SERVER)
                }
                report(result)
            }
        }

    private fun describe(invite: Invite): String {
        val created = Instant.ofEpochSecond(invite.createdAt)
        val expires = Instant.ofEpochSecond(invite.expiresAt)
        val usedBy = invite.usedBy?.let { "  used by $it" }.orEmpty()
        return "${invite.inviteId}  ${invite.status.name.lowercase()}  created $created  expires $expires$usedBy"
    }
}

/** The file an invite link goes to: new (never overwritten) and owner-only where POSIX permissions exist. */
internal object LinkFile {
    fun createEmpty(path: Path): FileChannel {
        val options = setOf(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        return try {
            if ("posix" in path.fileSystem.supportedFileAttributeViews()) {
                FileChannel.open(path, options, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
            } else {
                // Windows: the file inherits the folder's ACL, as the state dir does (#102 SEC-17).
                FileChannel.open(path, options)
            }
        } catch (ignored: FileAlreadyExistsException) {
            throw CliError("--link-file already exists; it is never overwritten", EXIT_REFUSED)
        } catch (e: IOException) {
            throw CliError("--link-file can't be created (${e::class.java.simpleName})", EXIT_REFUSED)
        }
    }

    fun write(
        channel: FileChannel,
        link: String,
    ) {
        val bytes = ByteBuffer.wrap((link + "\n").encodeToByteArray())
        while (bytes.hasRemaining()) channel.write(bytes)
        channel.force(true)
    }
}
