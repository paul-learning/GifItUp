package app.gifitup.server.db

import java.nio.file.Files
import java.nio.file.Path
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DatabaseTest {
    @Test
    fun connectsAsTheAppRoleAndCommitsOrRollsBack() {
        val url = TestPostgres.migratedDatabase()
        Database.connect(DatabaseConfig(url, APP_ROLE, TestPostgres.appPasswordFile())).use { database ->
            assertTrue(database.isReachable())

            assertFailsWith<IllegalStateException> {
                database.transaction { connection ->
                    connection.createStatement().executeUpdate(
                        "INSERT INTO accounts (id, username, created_at) VALUES (gen_random_uuid(), 'rolled_back', now())",
                    )
                    error("abort")
                }
            }
            database.transaction { connection ->
                connection.createStatement().executeUpdate(
                    "INSERT INTO accounts (id, username, created_at) VALUES (gen_random_uuid(), 'committed', now())",
                )
            }

            val names =
                database.transaction { connection ->
                    connection.createStatement().executeQuery("SELECT username FROM accounts").use { rows ->
                        buildList { while (rows.next()) add(rows.getString(1)) }
                    }
                }
            assertEquals(listOf("committed"), names)
        }
    }

    @Test
    fun duplicateThroughThePoolNamesNoUsernameInErrorsOrTheServerLog() {
        val url = TestPostgres.migratedDatabase()
        Database.connect(DatabaseConfig(url, APP_ROLE, TestPostgres.appPasswordFile())).use { database ->
            val insert = "INSERT INTO accounts (id, username, created_at) VALUES (gen_random_uuid(), 'pooldup_name', now())"
            database.transaction { it.createStatement().executeUpdate(insert) }

            val error = assertFailsWith<SQLException> { database.transaction { it.createStatement().executeUpdate(insert) } }

            assertEquals("23505", error.sqlState)
            assertFalse(generateSequence<Throwable>(error) { it.cause }.any { it.message.orEmpty().contains("pooldup_name") })
            // The statement text is logged (it contains the literal here), but no DETAIL line naming the key (#69 SEC-16).
            assertFalse(TestPostgres.serverLog().contains("Key (username)=(pooldup_name)"), "DETAIL reached the server log")
        }
    }

    @Test
    fun wrongPasswordFailsStartup() {
        val url = TestPostgres.migratedDatabase()
        val wrong = Files.createTempFile("wrong", "").also { Files.writeString(it, "x".repeat(MIN_SECRET_LENGTH)) }

        assertFailsWith<Exception> { Database.connect(DatabaseConfig(url, APP_ROLE, wrong)) }
    }

    @Test
    fun closedPoolIsNotReachable() {
        val url = TestPostgres.migratedDatabase()
        val database = Database.connect(DatabaseConfig(url, APP_ROLE, TestPostgres.appPasswordFile()))

        database.close()

        assertFalse(database.isReachable())
    }

    @Test
    fun configRejectsUrlsWithParametersOrOtherDrivers() {
        val file = Path.of("unused")
        listOf(
            "jdbc:postgresql://db:5432/gifitup?sslmode=disable",
            "jdbc:postgresql://db/gifitup?password=x",
            "jdbc:mysql://db:3306/gifitup",
            "jdbc:postgresql://db:5432/GifItUp",
            "",
        ).forEach { url -> assertFailsWith<IllegalArgumentException>(url) { DatabaseConfig(url, APP_ROLE, file) } }
        DatabaseConfig("jdbc:postgresql://postgres:5432/gifitup", APP_ROLE, file)
    }

    @Test
    fun configNamesMissingVariables() {
        val error = assertFailsWith<IllegalArgumentException> { DatabaseConfig.fromEnv(mapOf("DB_URL" to "jdbc:postgresql://p/g")) }

        assertEquals("DB_USER is not set", error.message)
    }

    @Test
    fun secretFileRules() {
        fun secret(content: String) = Files.createTempFile("secret", "").also { Files.writeString(it, content) }
        val good = "a".repeat(MIN_SECRET_LENGTH)

        assertEquals(good, String(readSecretFile(secret(good), "S")))
        assertEquals(good, String(readSecretFile(secret("$good\n"), "S")))
        assertEquals(good, String(readSecretFile(secret("$good\r\n"), "S")))
        listOf("short", "$good\n\n", "$good\nsecond", good + "é", "$good\t").forEach { content ->
            val error = assertFailsWith<IllegalArgumentException>(content) { readSecretFile(secret(content), "S") }
            assertFalse(error.message.orEmpty().contains(good), "the message must not echo the secret")
        }
        assertFailsWith<IllegalArgumentException> { readSecretFile(Path.of("does-not-exist"), "S") }
    }

    /** The KeyPackage claim and the upload cap rely on READ COMMITTED (#118 SEC-5): the pool pins it. */
    @Test
    fun transactionsRunAtReadCommitted() {
        val url = TestPostgres.migratedDatabase()
        // Make the database's own default something else: only the pool's setting keeps READ COMMITTED.
        TestPostgres.adminAt(url).use { admin ->
            val name = url.substringAfterLast('/')
            admin.createStatement().use { it.execute("ALTER DATABASE $name SET default_transaction_isolation = 'repeatable read'") }
        }
        Database.connect(DatabaseConfig(url, APP_ROLE, TestPostgres.appPasswordFile())).use { database ->
            database.transaction { connection ->
                assertEquals(java.sql.Connection.TRANSACTION_READ_COMMITTED, connection.transactionIsolation)
                connection.createStatement().use { s ->
                    s.executeQuery("SHOW transaction_isolation").use {
                        it.next()
                        assertEquals("read committed", it.getString(1))
                    }
                }
            }
        }
    }
}
