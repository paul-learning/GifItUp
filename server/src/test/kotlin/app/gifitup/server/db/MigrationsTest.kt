package app.gifitup.server.db

import java.sql.Connection
import java.sql.SQLException
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Migrations, role model and schema constraints against the production PostgreSQL image (ADR-0008, SEC-M0-6). */
class MigrationsTest {
    @Test
    fun migrationsApplyAndAreIdempotent() {
        val url = TestPostgres.freshDatabase()
        val migrations = TestPostgres.migrations(url)

        migrations.run(TestPostgres.appPassword.toCharArray())
        migrations.run(TestPostgres.appPassword.toCharArray())

        assertEquals(listOf("1", "2", "3"), migrations.appliedVersions())
    }

    @Test
    fun everyObjectIsOwnedByTheNologinOwnerRole() {
        val url = TestPostgres.migratedDatabase()
        TestPostgres.adminAt(url).use { admin ->
            assertEquals(
                setOf(OWNER_ROLE),
                admin.strings("SELECT DISTINCT tableowner FROM pg_tables WHERE schemaname IN ('gifitup', 'flyway')"),
            )
            assertEquals(
                setOf(OWNER_ROLE),
                admin.strings("SELECT DISTINCT pg_get_userbyid(nspowner) FROM pg_namespace WHERE nspname IN ('gifitup', 'flyway')"),
            )
            assertEquals(setOf("false"), admin.strings("SELECT rolcanlogin::text FROM pg_roles WHERE rolname = '$OWNER_ROLE'"))
        }
    }

    @Test
    fun appRoleHasNoElevatedAttributes() {
        val url = TestPostgres.migratedDatabase()
        TestPostgres.adminAt(url).use { admin ->
            val attributes =
                admin.strings(
                    "SELECT concat_ws(',', rolsuper, rolcreatedb, rolcreaterole, rolreplication, rolbypassrls, rolinherit) " +
                        "FROM pg_roles WHERE rolname = '$APP_ROLE'",
                )
            assertEquals(setOf("f,f,f,f,f,f"), attributes)
            assertEquals(emptySet(), admin.memberships(APP_ROLE), "the app role must not be a member of any role")
        }
    }

    @Test
    fun handMadeRoleChangesAreUndoneOnTheNextRun() {
        val url = TestPostgres.migratedDatabase()
        TestPostgres.adminAt(url).use { admin ->
            admin.createStatement().execute("GRANT $OWNER_ROLE TO $APP_ROLE")
            admin.createStatement().execute("GRANT pg_execute_server_program TO $APP_ROLE")
            admin.createStatement().execute("ALTER ROLE $APP_ROLE CREATEDB INHERIT")
        }

        TestPostgres.migrations(url).run(TestPostgres.appPassword.toCharArray())

        TestPostgres.adminAt(url).use { admin ->
            assertEquals(emptySet(), admin.memberships(APP_ROLE))
            assertEquals(
                setOf("f,f"),
                admin.strings("SELECT concat_ws(',', rolcreatedb, rolinherit) FROM pg_roles WHERE rolname = '$APP_ROLE'"),
            )
        }
        TestPostgres.appAt(url).use { app ->
            assertEquals("42501", assertFailsWith<SQLException> { app.createStatement().execute("SET ROLE $OWNER_ROLE") }.sqlState)
        }
    }

    @Test
    fun appRoleCannotReachOtherDatabases() {
        val url = TestPostgres.migratedDatabase()
        listOf("postgres", "template1").forEach { other ->
            val error = assertFailsWith<SQLException>(other) { TestPostgres.appAt(url.substringBeforeLast('/') + "/" + other).close() }
            assertEquals("42501", error.sqlState, "$other: ${error.sqlState}")
        }
    }

    @Test
    fun appRoleCanReadAndWriteRows() {
        val url = TestPostgres.migratedDatabase()
        TestPostgres.appAt(url).use { app ->
            val account = insertAccount(app, "alice")
            val device = insertDevice(app, account, ByteArray(32) { 1 })
            insertSession(app, device)

            assertEquals(setOf("alice"), app.strings("SELECT username FROM accounts"))
            app.createStatement().executeUpdate("UPDATE devices SET last_seen_at = now()")
            app.createStatement().executeUpdate("DELETE FROM accounts")
            assertEquals(emptySet(), app.strings("SELECT token_hash::text FROM sessions"), "sessions cascade with the account")
        }
    }

    @Test
    fun appRoleCannotChangeSchemaHistoryOrEscalate() {
        val url = TestPostgres.migratedDatabase()
        val forbidden =
            listOf(
                "CREATE TABLE gifitup.x (i int)",
                "CREATE TABLE public.x (i int)",
                "CREATE TEMP TABLE x (i int)",
                "CREATE SCHEMA s",
                "DROP TABLE gifitup.accounts",
                "ALTER TABLE gifitup.accounts ADD COLUMN x int",
                "TRUNCATE gifitup.accounts",
                "ALTER TABLE gifitup.key_packages ADD COLUMN x int",
                "TRUNCATE gifitup.key_packages",
                "DROP TABLE gifitup.key_packages",
                "SELECT * FROM flyway.flyway_schema_history",
                "UPDATE flyway.flyway_schema_history SET checksum = 0",
                "SET ROLE $OWNER_ROLE",
                "COPY (SELECT 1) TO PROGRAM 'true'",
                "ALTER ROLE $APP_ROLE SUPERUSER",
                "GRANT $OWNER_ROLE TO $APP_ROLE",
            )
        TestPostgres.appAt(url).use { app ->
            forbidden.forEach { sql ->
                val error = assertFailsWith<SQLException>(sql) { app.createStatement().execute(sql) }
                assertEquals("42501", error.sqlState, "$sql: expected insufficient_privilege, got ${error.sqlState}")
            }
        }
    }

    @Test
    fun usernameRuleIsEnforcedByTheDatabase() {
        val url = TestPostgres.migratedDatabase()
        TestPostgres.appAt(url).use { app ->
            listOf("ab", "Alice", "al ice", "a".repeat(33), "ålice", "alice\u0000").forEach { name ->
                val error = assertFailsWith<SQLException>(name) { insertAccount(app, name) }
                assertTrue(error.sqlState == "23514" || error.sqlState == "22021", "$name: ${error.sqlState}")
            }
            insertAccount(app, "a_b_3")
            insertAccount(app, "x".repeat(32))
        }
    }

    @Test
    fun duplicateUsernameFailsWithoutNamingItInTheError() {
        val url = TestPostgres.migratedDatabase()
        TestPostgres.appAt(url).use { app ->
            insertAccount(app, "uniquename")

            val error = assertFailsWith<SQLException> { insertAccount(app, "uniquename") }

            assertEquals("23505", error.sqlState)
            assertFalse(error.message.orEmpty().contains("uniquename"), "logServerErrorDetail must keep the key out")
            assertFalse(generateSequence(error.cause) { it.cause }.any { it.message.orEmpty().contains("uniquename") })
        }
    }

    @Test
    fun deviceKeyMustBe32BytesAndUnique() {
        val url = TestPostgres.migratedDatabase()
        TestPostgres.appAt(url).use { app ->
            val account = insertAccount(app, "bob")
            assertEquals("23514", assertFailsWith<SQLException> { insertDevice(app, account, ByteArray(31)) }.sqlState)
            insertDevice(app, account, ByteArray(32) { 7 })
            assertEquals("23505", assertFailsWith<SQLException> { insertDevice(app, account, ByteArray(32) { 7 }) }.sqlState)
        }
    }

    private fun insertAccount(
        app: Connection,
        username: String,
    ): UUID =
        UUID.randomUUID().also { id ->
            app.prepareStatement("INSERT INTO accounts (id, username, created_at) VALUES (?, ?, now())").use {
                it.setObject(1, id)
                it.setString(2, username)
                it.executeUpdate()
            }
        }

    private fun insertDevice(
        app: Connection,
        account: UUID,
        key: ByteArray,
    ): UUID =
        UUID.randomUUID().also { id ->
            app
                .prepareStatement(
                    "INSERT INTO devices (id, account_id, signature_key, created_at, last_seen_at) VALUES (?, ?, ?, now(), now())",
                ).use {
                    it.setObject(1, id)
                    it.setObject(2, account)
                    it.setBytes(3, key)
                    it.executeUpdate()
                }
        }

    private fun insertSession(
        app: Connection,
        device: UUID,
    ) {
        app.prepareStatement("INSERT INTO sessions (token_hash, device_id, created_at, expires_at) VALUES (?, ?, ?, ?)").use {
            val now = OffsetDateTime.now()
            it.setBytes(1, ByteArray(32) { 3 })
            it.setObject(2, device)
            it.setObject(3, now)
            it.setObject(4, now.plusHours(24))
            it.executeUpdate()
        }
    }

    private fun Connection.memberships(role: String): Set<String> =
        strings(
            "SELECT b.rolname FROM pg_auth_members m JOIN pg_roles b ON m.roleid = b.oid " +
                "JOIN pg_roles r ON m.member = r.oid WHERE r.rolname = '$role'",
        )

    private fun Connection.strings(sql: String): Set<String> =
        createStatement().use { statement ->
            statement.executeQuery(sql).use { rows -> buildSet { while (rows.next()) add(rows.getString(1)) } }
        }
}
