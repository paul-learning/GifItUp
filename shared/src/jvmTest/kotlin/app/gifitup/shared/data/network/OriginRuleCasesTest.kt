package app.gifitup.shared.data.network

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The client's copy of the canonical-origin rule against the cases the crate and the server share (#71 SEC-33,
 * #94 CR-4), so the three copies can't drift. The file is read from the server's test resources, like the crate does,
 * and declared as an input of this test task.
 */
class OriginRuleCasesTest {
    @Test
    fun clientRuleMatchesTheSharedCases() {
        val cases =
            File(CASES)
                .readLines()
                .filter { it.isNotBlank() && !it.startsWith("#") }
                .map { line ->
                    line[0] to
                        line
                            .substring(2)
                            .chunked(2)
                            .map { it.toInt(16).toByte() }
                            .toByteArray()
                            .decodeToString()
                }
        assertTrue(cases.size > 20, "too few cases: ${cases.size}")
        cases.forEach { (sign, origin) -> assertEquals(sign == '+', ApiEndpoint.isCanonicalOrigin(origin), origin) }
    }

    private companion object {
        const val CASES = "../server/src/test/resources/origin-rule-cases.txt"
    }
}
