package app.gifitup.server

import app.gifitup.server.db.DatabaseConfig
import app.gifitup.server.db.Migrations
import app.gifitup.server.db.readSecretFile
import app.gifitup.server.db.required
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.system.exitProcess

/**
 * Entry point of the one-shot `migrate` container (ADR-0008), run by `deploy.sh` before the server starts. It is the
 * only process that gets the superuser password; the server never does. Logs fixed lines only.
 */
fun main() {
    val log = LoggerFactory.getLogger("app.gifitup.server.Migrate")
    val env = System.getenv()
    val result =
        runCatching {
            // DB_URL/DB_ADMIN_USER are validated like the server's settings.
            val admin =
                DatabaseConfig(
                    jdbcUrl = env.required("DB_URL"),
                    user = env.required("DB_ADMIN_USER"),
                    passwordFile = Path.of(env.required("DB_ADMIN_PASSWORD_FILE")),
                )
            val appPasswordFile = Path.of(env.required("DB_APP_PASSWORD_FILE"))
            val migrations =
                Migrations(admin.jdbcUrl, admin.user, readSecretFile(admin.passwordFile, "DB_ADMIN_PASSWORD_FILE"))
            migrations.run(appPassword = readSecretFile(appPasswordFile, "DB_APP_PASSWORD_FILE"))
            migrations.appliedVersions()
        }
    result
        .onSuccess { versions -> log.info("event=migrate_ok applied={}", versions.joinToString(",")) }
        .onFailure { error ->
            // Configuration errors carry fixed messages that name the variable, never a value; everything else (SQL,
            // Flyway) logs only its class, as the server does.
            val detail = if (error is IllegalArgumentException) error.message else error::class.java.name
            log.error("event=migrate_failed detail={}", detail)
            exitProcess(1)
        }
}
