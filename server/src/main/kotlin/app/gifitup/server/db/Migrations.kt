package app.gifitup.server.db

import org.flywaydb.core.Flyway
import org.postgresql.PGConnection
import org.postgresql.ds.PGSimpleDataSource
import java.sql.Connection
import javax.sql.DataSource

/**
 * The one-shot migration (ADR-0008). Runs as the database superuser, only in the `migrate` container:
 * 1. creates or tightens the roles: [OWNER_ROLE] (NOLOGIN, owns the schemas) and [APP_ROLE] (LOGIN, DML only);
 * 2. sets the app role's password as a SCRAM verifier, computed client-side, so the cleartext never appears in SQL;
 * 3. runs Flyway with its sessions set to [OWNER_ROLE], so every object is owned by the role nobody can log in as.
 *
 * Everything is idempotent: the deploy runs it before every server start.
 */
class Migrations(
    private val adminUrl: String,
    private val adminUser: String,
    private val adminPassword: CharArray,
) {
    fun run(appPassword: CharArray) {
        adminConnection().use { connection ->
            connection.autoCommit = false
            bootstrapRoles(connection)
            // Client-side SCRAM: only the verifier goes over the wire, never the password (#12 round 0, SEC-4).
            connection.unwrap(PGConnection::class.java).alterUserPassword(APP_ROLE, appPassword, "scram-sha-256")
            connection.commit()
        }
        flyway().migrate()
    }

    /** Applied migrations' versions in order, for tests and the migrate log (Flyway's schema-creation entry has none). */
    fun appliedVersions(): List<String> = flyway().info().applied().mapNotNull { it.version?.version }

    private val adminDataSource: DataSource = dataSource(role = null)

    /** Flyway's connections start acting as the owner role, so every object it creates belongs to that role. */
    private val ownerDataSource: DataSource = dataSource(role = OWNER_ROLE)

    private fun dataSource(role: String?): DataSource =
        PGSimpleDataSource().apply {
            setURL(adminUrl)
            user = adminUser
            password = String(adminPassword)
            safeDriverProperties().forEach { (key, value) -> setProperty(key as String, value as String) }
            // Startup parameter: the session runs `SET ROLE` before Flyway sends its first statement.
            if (role != null) options = "-c role=$role"
        }

    private fun adminConnection(): Connection = adminDataSource.connection

    private fun flyway(): Flyway =
        Flyway
            .configure()
            .dataSource(ownerDataSource)
            .schemas(FLYWAY_SCHEMA, APP_SCHEMA)
            .defaultSchema(FLYWAY_SCHEMA)
            .createSchemas(true)
            .locations("classpath:db/migration")
            .validateMigrationNaming(true)
            .cleanDisabled(true)
            .baselineOnMigrate(false)
            .outOfOrder(false)
            .load()
}

/**
 * Creates the two roles if missing and pins their attributes every time, so a role tampered with by hand (or created
 * with defaults by an older migration) is brought back to least privilege. Role names are constants, never input.
 */
internal fun bootstrapRoles(connection: Connection) {
    connection.createStatement().use { statement ->
        statement.execute(
            """
            DO ${'$'}${'$'}
            BEGIN
              IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = '$OWNER_ROLE') THEN
                CREATE ROLE $OWNER_ROLE NOLOGIN;
              END IF;
              IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = '$APP_ROLE') THEN
                CREATE ROLE $APP_ROLE LOGIN;
              END IF;
              EXECUTE format('REVOKE ALL ON DATABASE %I FROM PUBLIC', current_database());
              EXECUTE format('GRANT CONNECT ON DATABASE %I TO $APP_ROLE', current_database());
              EXECUTE format('GRANT CREATE ON DATABASE %I TO $OWNER_ROLE', current_database());
            END
            ${'$'}${'$'};
            """.trimIndent(),
        )
        statement.execute(
            "ALTER ROLE $OWNER_ROLE NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS",
        )
        statement.execute(
            "ALTER ROLE $APP_ROLE LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS " +
                "NOINHERIT CONNECTION LIMIT $APP_CONNECTION_LIMIT",
        )
        // PostgreSQL 15+ already denies CREATE on public to PUBLIC; stated explicitly so it can't silently regress.
        statement.execute("REVOKE CREATE ON SCHEMA public FROM PUBLIC")
    }
}

/** Pool size plus headroom; also caps what a leaked app credential can open. */
private const val APP_CONNECTION_LIMIT = 20
