package app.gifitup.server.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.sql.Connection
import java.util.Properties

/** Schema that holds the app's tables (ADR-0008). Flyway's history lives in [FLYWAY_SCHEMA], out of the app's reach. */
internal const val APP_SCHEMA = "gifitup"
internal const val FLYWAY_SCHEMA = "flyway"

/** Least-privilege role the server connects as, and the NOLOGIN role that owns the schema (ADR-0008, SEC-M0-6). */
internal const val APP_ROLE = "gifitup_app"
internal const val OWNER_ROLE = "gifitup_owner"

/**
 * The server's connection pool, as the app role. Repositories (PR D of #12) run their SQL through [transaction] with
 * prepared statements only.
 */
class Database internal constructor(
    private val dataSource: HikariDataSource,
) : AutoCloseable {
    /** Runs [block] in one transaction: commit on success, rollback on any exception (which is rethrown). */
    fun <T> transaction(block: (Connection) -> T): T =
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            var committed = false
            try {
                block(connection).also {
                    connection.commit()
                    committed = true
                }
            } finally {
                if (!committed) connection.rollback()
            }
        }

    /** True if a pooled connection answers within [timeoutSeconds]. Never throws. */
    fun isReachable(timeoutSeconds: Int = 1): Boolean =
        runCatching { dataSource.connection.use { it.isValid(timeoutSeconds) } }.getOrDefault(false)

    override fun close() = dataSource.close()

    companion object {
        private const val POOL_SIZE = 10
        private const val CONNECTION_TIMEOUT_MILLIS = 2_000L

        /** Opens the pool. Fails if the database can't be reached or the credentials are wrong. */
        fun connect(config: DatabaseConfig): Database {
            val password = readSecretFile(config.passwordFile, "DB_PASSWORD_FILE")
            val hikari =
                HikariConfig().apply {
                    jdbcUrl = config.jdbcUrl
                    username = config.user
                    // Hikari keeps its own String copy for reconnects (masked in its debug config dump).
                    this.password = String(password)
                    schema = APP_SCHEMA
                    maximumPoolSize = POOL_SIZE
                    connectionTimeout = CONNECTION_TIMEOUT_MILLIS
                    poolName = "gifitup"
                    dataSourceProperties = safeDriverProperties()
                }
            password.fill('\u0000')
            return Database(HikariDataSource(hikari))
        }
    }
}

/**
 * pgjdbc settings for every connection, server and migration alike (#12 round 0, SEC-5):
 * - `logServerErrorDetail=false`: a constraint violation's `DETAIL` (`Key (username)=(alice) already exists`) stays
 *   out of exception messages, so nothing that logs an exception can leak a username.
 * - `ApplicationName`: shows up in `pg_stat_activity`, nothing more.
 */
internal fun safeDriverProperties(): Properties =
    Properties().apply {
        setProperty("logServerErrorDetail", "false")
        setProperty("ApplicationName", "gifitup-server")
    }
