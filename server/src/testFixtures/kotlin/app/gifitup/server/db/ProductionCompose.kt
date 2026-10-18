package app.gifitup.server.db

import java.nio.file.Files
import java.nio.file.Path

/** The Postgres service as production runs it, read from `deploy/docker-compose.yml` so tests can't drift from it. */
internal object ProductionCompose {
    private val composeLines: List<String> by lazy {
        Files.readAllLines(Path.of("..", "deploy", "docker-compose.yml")).map { it.trim() }
    }

    fun postgresImage(): String {
        val image = composeLines.single { it.startsWith("image: postgres:") }.removePrefix("image: ")
        check(image.matches(Regex("^postgres:[0-9a-z.-]+@sha256:[0-9a-f]{64}$"))) { "compose pins postgres by digest" }
        return image
    }

    /** The postgres service's `command:` (the only one in the compose file), e.g. `postgres -c log_error_verbosity=terse`. */
    fun postgresCommand(): List<String> =
        composeLines
            .single { it.startsWith("command: [") }
            .removePrefix("command: [")
            .removeSuffix("]")
            .split(",")
            .map { it.trim().removeSurrounding("\"") }
}
