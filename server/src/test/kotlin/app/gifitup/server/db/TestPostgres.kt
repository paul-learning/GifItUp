package app.gifitup.server.db

import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID

/**
 * One PostgreSQL container for the whole test JVM, with the exact image (tag and digest) that production runs: it is
 * read from `deploy/docker-compose.yml`, so the two can't drift. Ryuk is disabled (SEC-13), so a shutdown hook stops
 * the container.
 *
 * Each test gets its own fresh database ([freshDatabase]), so migrations and role checks start from zero. Roles are
 * cluster-wide in PostgreSQL, so every database shares [OWNER_ROLE] and [APP_ROLE]; the migration re-pins their
 * attributes each run, exactly as it does on the VM.
 */
internal object TestPostgres {
    private const val ADMIN_USER = "gifitup"
    private val adminPassword = "admin-" + UUID.randomUUID()

    val appPassword: String = "app-pw-with-'quotes\"-and-\\backslash-" + UUID.randomUUID()

    private val container: PostgreSQLContainer by lazy {
        PostgreSQLContainer(DockerImageName.parse(productionImage()).asCompatibleSubstituteFor("postgres"))
            .withUsername(ADMIN_USER)
            .withPassword(adminPassword)
            .withDatabaseName("postgres")
            .also { it.start() }
            .also { started -> Runtime.getRuntime().addShutdownHook(Thread { started.stop() }) }
    }

    /** Creates an empty database and returns its JDBC URL. */
    fun freshDatabase(): String {
        val name = "t_" + UUID.randomUUID().toString().replace("-", "")
        admin("postgres").use { it.createStatement().execute("CREATE DATABASE $name") }
        return "jdbc:postgresql://${container.host}:${container.firstMappedPort}/$name"
    }

    fun migrations(url: String) = Migrations(url, ADMIN_USER, adminPassword.toCharArray())

    /** Applies the migrations to a fresh database and returns its URL. */
    fun migratedDatabase(): String = freshDatabase().also { migrations(it).run(appPassword.toCharArray()) }

    fun admin(database: String): Connection =
        DriverManager.getConnection(
            "jdbc:postgresql://${container.host}:${container.firstMappedPort}/$database",
            ADMIN_USER,
            adminPassword,
        )

    fun adminAt(url: String): Connection = DriverManager.getConnection(url, ADMIN_USER, adminPassword)

    /** A connection as the app role, with the same driver settings as the server's pool. */
    fun appAt(url: String): Connection =
        DriverManager.getConnection(
            url,
            safeDriverProperties().apply {
                setProperty("user", APP_ROLE)
                setProperty("password", appPassword)
                setProperty("currentSchema", APP_SCHEMA)
            },
        )

    /** Writes [appPassword] to a temporary secret file, as Docker would mount it. */
    fun appPasswordFile(): Path = Files.createTempFile("app-password", "").also { Files.writeString(it, appPassword + "\n") }

    private fun productionImage(): String {
        val compose = Path.of("..", "deploy", "docker-compose.yml")
        val image =
            Files
                .readAllLines(compose)
                .map { it.trim() }
                .single { it.startsWith("image: postgres:") }
                .removePrefix("image: ")
        check(image.matches(Regex("^postgres:[0-9a-z.-]+@sha256:[0-9a-f]{64}$"))) { "compose pins postgres by digest" }
        return image
    }
}
