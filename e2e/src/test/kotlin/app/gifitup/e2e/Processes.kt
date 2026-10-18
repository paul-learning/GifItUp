package app.gifitup.e2e

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit
import kotlin.io.path.readText
import kotlin.test.fail

/** What a finished process left: its exit code and output. */
class Finished(
    val exit: Int,
    val stdout: String,
    val stderr: String,
)

/**
 * Runs the entry points under test as processes. Each one starts with an **empty environment** plus what it needs
 * (#79 round-0 SEC-6), so a developer's exported `DB_*` or a CI variable can't change what it does. Output goes to
 * files in [work], never through a pipe, and every name/file pair is kept for the output checks; nothing here puts
 * output into an assertion message (#79 round-0 SEC-9).
 */
class Processes(
    private val work: Path,
) {
    private val captured = mutableListOf<Pair<String, () -> String>>()
    private var count = 0

    /** Output that must never hold a secret: everything except what [run]'s `excludeStdout` left out on purpose. */
    fun capturedOutput(): List<Pair<String, String>> = captured.map { (name, read) -> name to read() }

    /** Adds output the test filtered itself (Admin's stdout without the link line, #104 SEC-2) to the checks. */
    fun record(
        name: String,
        text: String,
    ) {
        captured += name to { text }
    }

    fun start(
        name: String,
        command: List<String>,
        env: Map<String, String>,
    ): Process {
        val out = work.resolve("${++count}-$name.out")
        captured += name to { out.readText() }
        return ProcessBuilder(command)
            .apply {
                environment().clear()
                environment().putAll(baseEnvironment + env)
            }.redirectErrorStream(true)
            .redirectOutput(out.toFile())
            .start()
            .also { it.outputStream.close() }
    }

    /**
     * Runs [command] to the end. [stdin] is written and closed first. With [excludeStdout] the stdout is returned but
     * left out of the output checks: only for Admin's `create-invite`, whose stdout holds the invite link by design (the
     * test then [record]s the rest).
     */
    fun run(
        name: String,
        command: List<String>,
        env: Map<String, String>,
        stdin: String = "",
        excludeStdout: Boolean = false,
    ): Finished {
        val n = ++count
        val out = work.resolve("$n-$name.stdout")
        val err = work.resolve("$n-$name.stderr")
        if (!excludeStdout) captured += "$name stdout" to { out.readText() }
        captured += "$name stderr" to { err.readText() }
        val process =
            ProcessBuilder(command)
                .apply {
                    environment().clear()
                    environment().putAll(baseEnvironment + env)
                }.redirectOutput(out.toFile())
                .redirectError(err.toFile())
                .start()
        process.outputStream.use { it.write(stdin.toByteArray()) }
        if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            fail("$name didn't finish within $TIMEOUT_SECONDS s")
        }
        return Finished(process.exitValue(), out.readText(), err.readText())
    }

    /** A file only the owner can read, as Docker mounts a secret. */
    fun secretFile(
        name: String,
        content: String,
    ): Path {
        val file = work.resolve(name)
        Files.writeString(file, content + "\n")
        if ("posix" in file.fileSystem.supportedFileAttributeViews()) {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"))
        }
        return file
    }

    companion object {
        const val TIMEOUT_SECONDS = 120L

        val windows = System.getProperty("os.name").startsWith("Windows")

        /** The start scripts need a `PATH` (and Windows its system dirs); the JVM comes from `JAVA_HOME`. */
        private val baseEnvironment: Map<String, String> =
            System.getenv().filterKeys { it in setOf("PATH", "Path", "SystemRoot", "TEMP", "TMP") } +
                ("JAVA_HOME" to System.getProperty("java.home"))

        val java: String = Path.of(System.getProperty("java.home"), "bin", if (windows) "java.exe" else "java").toString()
    }
}

/**
 * [text] with every known secret, and anything shaped like an invite code (22 base64url characters) or a session
 * token (43), replaced, for the dump a failing test prints (#79 round-0 SEC-9). Device ids (UUIDs, 36) stay readable.
 */
fun redact(
    text: String,
    secrets: Collection<String>,
): String =
    secrets
        .filter { it.isNotEmpty() }
        .fold(text) { acc, secret -> acc.replace(secret, "<redacted>") }
        .replace(SECRET_SHAPED, "<redacted>")

private val SECRET_SHAPED = Regex("(?<![A-Za-z0-9_-])([A-Za-z0-9_-]{43}|[A-Za-z0-9_-]{22})(?![A-Za-z0-9_-])")

/** A session token's shape: 43 base64url characters (32 bytes) on their own (ADR-0009). */
val TOKEN_SHAPED = Regex("(?<![A-Za-z0-9_-])[A-Za-z0-9_-]{43}(?![A-Za-z0-9_-])")
