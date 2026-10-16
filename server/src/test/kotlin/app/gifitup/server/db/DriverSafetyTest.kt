package app.gifitup.server.db

import java.sql.DriverManager
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * #69 SEC-15: a container on the shared `proxy` network can answer for the hostname `postgres`. The driver must refuse
 * any authentication method but SCRAM, so a fake server can't ask for the password in cleartext or MD5, and can't
 * accept the connection without authentication at all.
 */
class DriverSafetyTest {
    @Test
    fun driverRefusesCleartextPasswordAuthentication() = assertRefused(hostAuthMethod = "password")

    @Test
    fun driverRefusesMd5Authentication() = assertRefused(hostAuthMethod = "md5")

    @Test
    fun driverRefusesAServerThatAsksForNothing() = assertRefused(hostAuthMethod = "trust")

    private fun assertRefused(hostAuthMethod: String) {
        val container =
            TestPostgres
                .productionContainer()
                .withEnv("POSTGRES_HOST_AUTH_METHOD", hostAuthMethod)
                // The image's initdb otherwise stores a SCRAM verifier that md5 auth can't use.
                .withEnv("POSTGRES_INITDB_ARGS", "--auth-host=$hostAuthMethod")
        container.start()
        try {
            val url = "jdbc:postgresql://${container.host}:${container.firstMappedPort}/postgres"
            // The right password: without requireAuth, every one of these connections would succeed.
            val properties =
                safeDriverProperties().apply {
                    setProperty("user", container.username)
                    setProperty("password", container.password)
                }

            val error = assertFailsWith<SQLException>(hostAuthMethod) { DriverManager.getConnection(url, properties).close() }

            assertTrue(error.message.orEmpty().contains("requireAuth"), "refused by the driver's auth policy: ${error.message}")
        } finally {
            container.stop()
        }
    }
}
