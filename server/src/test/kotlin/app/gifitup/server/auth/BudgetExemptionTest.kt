package app.gifitup.server.auth

import io.ktor.http.HttpMethod
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The budget exemption matcher (#77 SEC-70, SEC-71): exact method, whole path. */
class BudgetExemptionTest {
    @Test
    fun logoutIsExemptOnlyAsPostOnTheExactPath() {
        val exempt = { method: HttpMethod, path: String -> BUDGET_EXEMPTIONS.any { it.matches(method, path) } }

        assertTrue(exempt(HttpMethod.Post, "/auth/logout"))
        assertFalse(exempt(HttpMethod.Get, "/auth/logout"))
        assertFalse(exempt(HttpMethod.Delete, "/auth/logout"))
        assertFalse(exempt(HttpMethod.Post, "/auth/logout/"))
        assertFalse(exempt(HttpMethod.Post, "/auth/logoutx"))
        assertFalse(exempt(HttpMethod.Post, "/devices"))
    }

    @Test
    fun aTemplatedRouteMatchesOneSegmentOnly() {
        val devices = BudgetExemption(HttpMethod.Delete, Regex("/devices/[^/]+"))

        assertTrue(devices.matches(HttpMethod.Delete, "/devices/123e4567-e89b-12d3-a456-426614174000"))
        assertFalse(devices.matches(HttpMethod.Delete, "/devices/"))
        assertFalse(devices.matches(HttpMethod.Delete, "/devices/a/b"))
        assertFalse(devices.matches(HttpMethod.Get, "/devices/a"))
    }
}
