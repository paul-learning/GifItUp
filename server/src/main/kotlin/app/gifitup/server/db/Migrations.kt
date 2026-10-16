package app.gifitup.server.db

import org.flywaydb.core.Flyway
import org.postgresql.PGConnection
import org.postgresql.ds.PGSimpleDataSource
import java.sql.Connection
import javax.sql.DataSource

/**
 * The one-shot migration (ADR-0008). Runs as the database superuser, only in the `migrate` container:
 * 1. creates or tightens the roles: [OWNER_ROLE] (NOLOGIN, owns the schemas) and [APP_ROLE] (LOGIN, DML only);
 * 2. runs Flyway with its sessions set to [OWNER_ROLE], so every object is owned by the role nobody can log in as;
 * 3. sets the app role's password as a SCRAM verifier, computed client-side, so the cleartext never appears in SQL.
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
            connection.commit()
        }
        flyway().migrate()
        // Last, so a failed migration leaves the running server's credentials alone, even when the owner just rotated
        // the password file (#69 CR-4). Client-side SCRAM: only the verifier goes over the wire (#12 round 0, SEC-4).
        adminConnection().use { connection ->
            connection.unwrap(PGConnection::class.java).alterUserPassword(APP_ROLE, appPassword, "scram-sha-256")
        }
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
            DECLARE
              membership record;
              db record;
            BEGIN
              IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = '$OWNER_ROLE') THEN
                CREATE ROLE $OWNER_ROLE NOLOGIN;
              END IF;
              IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = '$APP_ROLE') THEN
                CREATE ROLE $APP_ROLE LOGIN;
              END IF;
              -- Neither role may be a member of any other role: a hand-made GRANT (e.g. owner TO app) would allow
              -- SET ROLE, which NOINHERIT doesn't stop (#69 CR-1).
              FOR membership IN
                SELECT granted.rolname AS granted_role, member.rolname AS member_role, grantor.rolname AS grantor_role
                FROM pg_auth_members m
                JOIN pg_roles granted ON granted.oid = m.roleid
                JOIN pg_roles member ON member.oid = m.member
                JOIN pg_roles grantor ON grantor.oid = m.grantor
                WHERE member.rolname IN ('$OWNER_ROLE', '$APP_ROLE')
              LOOP
                -- PostgreSQL 16+ records memberships per grantor; revoke each one by its grantor.
                EXECUTE format('REVOKE %I FROM %I GRANTED BY %I', membership.granted_role, membership.member_role,
                  membership.grantor_role);
              END LOOP;
              -- Every database of this cluster, not only ours: the app role must not reach `postgres` or `template1`
              -- either (#69 SEC-19). The superuser connects regardless.
              FOR db IN SELECT datname FROM pg_database WHERE datallowconn LOOP
                EXECUTE format('REVOKE ALL ON DATABASE %I FROM PUBLIC', db.datname);
              END LOOP;
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
