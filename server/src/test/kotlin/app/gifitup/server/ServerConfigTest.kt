package app.gifitup.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class ServerConfigTest {
    @Test
    fun defaultsWhenUnset() {
        assertEquals(ServerConfig(), ServerConfig.fromEnv(emptyMap()))
        assertEquals(ServerConfig.DEFAULT_PORT, ServerConfig.fromEnv(emptyMap()).port)
    }

    @Test
    fun readsPort() {
        assertEquals(9090, ServerConfig.fromEnv(mapOf("PORT" to "9090")).port)
    }

    @Test
    fun invalidPortFailsStartupInsteadOfFallingBack() {
        listOf("", "0", "080", "65536", "99999", "8080 ", "-1", "abc", "8_080").forEach { raw ->
            assertFailsWith<IllegalArgumentException>(raw) { ServerConfig.fromEnv(mapOf("PORT" to raw)) }
        }
    }

    @Test
    fun errorMessageNamesTheVariableNotTheValue() {
        val error = assertFailsWith<IllegalArgumentException> { ServerConfig.fromEnv(mapOf("PORT" to "secret-ish")) }

        assertFalse(error.message.orEmpty().contains("secret-ish"))
    }

    @Test
    fun limitsMustBePositive() {
        assertFailsWith<IllegalArgumentException> { HttpLimits(maxHeaderSize = 0) }
        assertFailsWith<IllegalArgumentException> { HttpLimits(requestDeadlineSeconds = 0) }
        assertFailsWith<IllegalArgumentException> { HttpLimits(defaultBodyLimitBytes = 0) }
    }
}
