package app.gifitup.server.auth

import app.gifitup.keypackage.hex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The crate's rule (`is_canonical_origin` in crypto/src/auth.rs) on the shared cases file (#71 SEC-33). */
class CanonicalOriginTest {
    @Test
    fun matchesTheCratesRuleOnTheSharedCases() {
        val lines =
            checkNotNull(javaClass.getResource("/origin-rule-cases.txt"))
                .readText()
                .lines()
                .filter { it.isNotEmpty() && !it.startsWith("#") }
        lines.forEach { line ->
            val origin = hex(line.substring(1).trim()).decodeToString()
            assertEquals(line.startsWith("+"), CanonicalOrigin.isCanonical(origin), origin)
        }
        assertTrue(lines.size >= 20, "the shared file lost cases")
    }
}
