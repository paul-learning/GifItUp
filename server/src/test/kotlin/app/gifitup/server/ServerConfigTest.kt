package app.gifitup.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class ServerConfigTest {
    private val origin = mapOf("GIFITUP_PUBLIC_URL" to "https://gifitup.example.org")

    @Test
    fun defaultsWhenUnset() {
        assertEquals(testConfig(), ServerConfig.fromEnv(origin))
        assertEquals(ServerConfig.DEFAULT_PORT, ServerConfig.fromEnv(origin).port)
    }

    @Test
    fun readsPort() {
        assertEquals(9090, ServerConfig.fromEnv(origin + ("PORT" to "9090")).port)
    }

    /** #70 SEC-25: the origin signatures are checked against comes from configuration, and only in canonical form. */
    @Test
    fun publicOriginIsRequiredAndMustBeCanonical() {
        assertEquals(
            "https://localhost:8443",
            ServerConfig.fromEnv(mapOf("GIFITUP_PUBLIC_URL" to "https://localhost:8443")).publicOrigin.text,
        )
        assertFailsWith<IllegalArgumentException> { ServerConfig.fromEnv(emptyMap()) }
        listOf("https://gifitup.example.org/", "https://GifItUp.example.org", "http://gifitup.example.org", "https://x.org:443", "")
            .forEach { bad ->
                val error = assertFailsWith<IllegalArgumentException>(bad) { ServerConfig.fromEnv(mapOf("GIFITUP_PUBLIC_URL" to bad)) }
                assertFalse(bad.isNotEmpty() && error.message.orEmpty().contains(bad), "the message must not echo the value")
            }
    }

    @Test
    fun invalidPortFailsStartupInsteadOfFallingBack() {
        listOf("", "0", "080", "65536", "99999", "8080 ", "-1", "abc", "8_080").forEach { raw ->
            assertFailsWith<IllegalArgumentException>(raw) { ServerConfig.fromEnv(origin + ("PORT" to raw)) }
        }
    }

    @Test
    fun errorMessageNamesTheVariableNotTheValue() {
        val error = assertFailsWith<IllegalArgumentException> { ServerConfig.fromEnv(origin + ("PORT" to "secret-ish")) }

        assertFalse(error.message.orEmpty().contains("secret-ish"))
    }

    @Test
    fun limitsMustBePositive() {
        assertFailsWith<IllegalArgumentException> { HttpLimits(maxHeaderSize = 0) }
        assertFailsWith<IllegalArgumentException> { HttpLimits(requestDeadlineSeconds = 0) }
        assertFailsWith<IllegalArgumentException> { HttpLimits(defaultBodyLimitBytes = 0) }
    }
}
