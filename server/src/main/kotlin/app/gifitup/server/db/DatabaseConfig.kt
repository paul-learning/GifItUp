package app.gifitup.server.db

import java.nio.file.Files
import java.nio.file.Path

/**
 * Where and as whom the server connects (ADR-0008). The password stays in its file until a pool or the migration opens
 * a connection, so it never sits in a data class, a `toString()` or an environment variable.
 */
data class DatabaseConfig(
    val jdbcUrl: String,
    val user: String,
    val passwordFile: Path,
) {
    init {
        require(jdbcUrl.matches(JDBC_URL_PATTERN)) { "DB_URL must be jdbc:postgresql://host:port/database" }
        require(user.matches(ROLE_PATTERN)) { "DB_USER is not a valid role name" }
    }

    companion object {
        /** Reads the server's (least-privilege app role) connection settings. All three are required. */
        fun fromEnv(env: Map<String, String>): DatabaseConfig =
            DatabaseConfig(
                jdbcUrl = env.required("DB_URL"),
                user = env.required("DB_USER"),
                passwordFile = Path.of(env.required("DB_PASSWORD_FILE")),
            )

        // No query parameters: connection properties are set in code, so a URL can't switch off a safe default.
        private val JDBC_URL_PATTERN = Regex("^jdbc:postgresql://[A-Za-z0-9.-]+(:[0-9]{1,5})?/[a-z_][a-z0-9_]{0,62}$")
        internal val ROLE_PATTERN = Regex("^[a-z_][a-z0-9_]{0,62}$")
    }
}

internal fun Map<String, String>.required(name: String): String = requireNotNull(this[name]) { "$name is not set" }

/**
 * Reads a password from a Docker secret file. One trailing newline is tolerated (editors add it); anything else that
 * isn't printable ASCII, or a password shorter than [MIN_SECRET_LENGTH], fails startup. Error messages name the file's
 * role, never its content.
 */
internal fun readSecretFile(
    path: Path,
    name: String,
): CharArray {
    require(Files.isRegularFile(path)) { "$name: secret file missing" }
    val raw = Files.readString(path).removeSuffix("\n").removeSuffix("\r")
    require(raw.length >= MIN_SECRET_LENGTH) { "$name: secret too short" }
    require(raw.all { it in ' '..'~' }) { "$name: secret must be printable ASCII on one line" }
    return raw.toCharArray()
}

/** `openssl rand -base64 32` gives 44 characters; anything much shorter is a mistake. */
internal const val MIN_SECRET_LENGTH = 24
