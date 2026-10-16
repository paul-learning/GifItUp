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
    // Configuration first: its errors are our own fixed messages, naming a variable and never a value.
    val setup =
        runCatching {
            val adminUser = env.required("DB_ADMIN_USER")
            require(adminUser.matches(DatabaseConfig.ROLE_PATTERN)) { "DB_ADMIN_USER is not a valid role name" }
            // DB_URL is validated like the server's.
            val admin = DatabaseConfig(env.required("DB_URL"), adminUser, Path.of(env.required("DB_ADMIN_PASSWORD_FILE")))
            val adminPassword = readSecretFile(admin.passwordFile, "DB_ADMIN_PASSWORD_FILE")
            val appPassword = readSecretFile(Path.of(env.required("DB_APP_PASSWORD_FILE")), "DB_APP_PASSWORD_FILE")
            Migrations(admin.jdbcUrl, admin.user, adminPassword) to appPassword
        }.getOrElse { error ->
            // Only our own require() messages are logged; anything else (I/O on a secret file) by class.
            val detail = if (error is IllegalArgumentException) error.message else error::class.java.name
            log.error("event=migrate_failed stage=config detail={}", detail)
            exitProcess(1)
        }
    // The migration itself: SQL and Flyway messages can quote data, so only the exception class is logged.
    runCatching {
        val (migrations, appPassword) = setup
        migrations.run(appPassword)
        migrations.appliedVersions()
    }.onSuccess { versions -> log.info("event=migrate_ok applied={}", versions.joinToString(",")) }
        .onFailure { error ->
            log.error("event=migrate_failed stage=migrate exception={}", error::class.java.name)
            exitProcess(1)
        }
}
