package app.gifitup.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ConnectionTest {
    @Test
    fun httpsDerivesTheOriginFromTheServer() {
        val endpoint = Connection.endpoint("https://gifitup.app", origin = null)
        assertEquals("https://gifitup.app", endpoint.origin)
        assertEquals("https://gifitup.app:8443", Connection.endpoint("https://gifitup.app:8443", "https://gifitup.app:8443").origin)
    }

    @Test
    fun loopbackTakesTheOriginFromTheFlag() {
        assertEquals("https://e2e.invalid", Connection.endpoint("http://127.0.0.1:8080", "https://e2e.invalid").origin)
        assertEquals(
            "ApiEndpoint(origin=https://e2e.invalid, baseUrl=http://[::1]:9)",
            Connection.endpoint("http://[::1]:9", "https://e2e.invalid").toString(),
        )
    }

    @Test
    fun everythingElseIsRefused() {
        val cases =
            listOf(
                "https://gifitup.app" to "https://other.app", // a signature for one origin, sent to another host (#79 SEC-1)
                "https://GifItUp.app" to null,
                "https://gifitup.app:443" to null,
                "https://gifitup.app/" to null,
                "https://gifitup.app/api" to null,
                "https://gifitup.app?x=1" to null,
                "https://gifitup.app#x" to null,
                "https://user@gifitup.app" to null,
                "http://gifitup.app:80" to "https://gifitup.app",
                "http://localhost:8080" to "https://e2e.invalid", // resolved names, never: literals only (#79 SEC-7)
                "http://127.0.0.1.evil.com:80" to "https://e2e.invalid",
                "http://127.0.0.1@evil.com:80" to "https://e2e.invalid",
                "http://[::1]:1@evil" to "https://e2e.invalid",
                "http://127.0.0.2:80" to "https://e2e.invalid",
                "http://127.0.0.1" to "https://e2e.invalid", // no port
                "http://127.0.0.1:8080" to null, // no origin
                "http://127.0.0.1:8080" to "http://e2e.invalid",
                "ftp://127.0.0.1:21" to "https://e2e.invalid",
                "HTTPS://gifitup.app" to null,
                "mailto:x@gifitup.app" to null,
                "not a url" to null,
            )
        cases.forEach { (server, origin) ->
            val error = assertFailsWith<CliError>("$server $origin") { Connection.endpoint(server, origin) }
            assertEquals(EXIT_USAGE, error.exit)
        }
    }
}
