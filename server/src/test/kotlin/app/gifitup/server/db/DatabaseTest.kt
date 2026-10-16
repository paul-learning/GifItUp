package app.gifitup.server.db

import java.nio.file.Files
import java.nio.file.Path
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
}
