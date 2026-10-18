package app.gifitup.shared.domain.auth

import app.gifitup.shared.domain.auth.TestAccountGuard.Refusal
import kotlin.test.Test
import kotlin.test.assertEquals

class TestAccountGuardTest {
    @Test
    fun testOriginsAreAlwaysAllowed() {
        listOf("https://e2e.invalid", "https://gifitup.test", "https://a.b.test:8443", "https://localhost", "https://localhost:8443")
            .forEach { assertEquals(null, TestAccountGuard.check(it, "ann", remoteTestAccount = false), it) }
    }

    @Test
    fun otherOriginsNeedTheFlagAndATestUsername() {
        val prod = "https://gifitup.app"
        assertEquals(Refusal.NOT_A_TEST_ORIGIN, TestAccountGuard.check(prod, "cli_smoke", remoteTestAccount = false))
        assertEquals(Refusal.NOT_A_TEST_USERNAME, TestAccountGuard.check(prod, "ann", remoteTestAccount = true))
        assertEquals(Refusal.NOT_A_TEST_USERNAME, TestAccountGuard.check(prod, "clismoke", remoteTestAccount = true))
        assertEquals(null, TestAccountGuard.check(prod, "cli_smoke", remoteTestAccount = true))
    }

    @Test
    fun lookalikesAreNotTestOrigins() {
        listOf(
            "https://invalid",
            "https://test",
            "https://gifitup.invalid.app",
            "https://gifitup.test.app",
            "https://localhost.app",
            "https://mytest",
            "http://e2e.invalid",
            "https://xlocalhost",
            // Not canonical, so not an origin at all (#102 CR-1, SEC-14).
            "https://gifitup.app/.test",
            "https://gifitup.app/x.test",
            "https://gifitup.app?.invalid",
            "https://gifitup.app#.invalid",
            "https://u@gifitup.app?.test",
            "https://localhost:443",
            "https://E2E.invalid",
            "https://e2e.invalid/",
        ).forEach { assertEquals(Refusal.NOT_A_TEST_ORIGIN, TestAccountGuard.check(it, "cli_x", remoteTestAccount = false), it) }
    }
}
