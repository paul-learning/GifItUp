package app.gifitup.server.auth

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The crate's table (`only_canonical_origins_are_accepted` in crypto/src/auth.rs): both sides accept the same origins. */
class CanonicalOriginTest {
    @Test
    fun acceptsExactlyTheCanonicalSpelling() {
        listOf("https://gifitup.example.org", "https://localhost:8443", "https://a.b-c.d:1", "https://10.0.0.1").forEach {
            assertTrue(CanonicalOrigin.isCanonical(it), it)
        }
        listOf(
            "https://gifitup.example.org/",
            "https://GifItUp.example.org",
            "http://gifitup.example.org",
            "https://gifitup.example.org:443",
            "https://gifitup.example.org:0443",
            "https://gifitup.example.org:65536",
            "https://gifitup.example.org:",
            "https://gifitup.example.org/path",
            "https://gifitup.example.org?x",
            "https://user@gifitup.example.org",
            "https://.example.org",
            "https://example..org",
            "https://-a.org",
            "https://a-.org",
            "https://",
            " https://gifitup.example.org",
            "https://" + "a".repeat(256) + ".org",
        ).forEach { assertFalse(CanonicalOrigin.isCanonical(it), it) }
    }
}
