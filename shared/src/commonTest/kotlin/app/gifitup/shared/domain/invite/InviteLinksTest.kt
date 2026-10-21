package app.gifitup.shared.domain.invite

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class InviteLinksTest {
    private val origin = "https://gifitup.example"
    private val code = "AAAAAAAAAAAAAAAAAAAAAA"

    private fun parse(text: String) = InviteLinks.parse(text, origin)

    @Test
    fun aLinkForTheConfiguredOriginGivesItsCode() {
        val link = assertIs<InviteInput.Link>(parse("  $origin/invite#$code\n"))
        assertEquals(code, link.code.reveal())
        assertEquals("$origin/invite#$code", InviteLinks.build(origin, link.code))
    }

    @Test
    fun aBareCodeIsACodeWithoutAnOrigin() {
        assertEquals(code, assertIs<InviteInput.Code>(parse("\t$code ")).code.reveal())
    }

    @Test
    fun aWellFormedLinkForAnotherOriginIsReportedAsSuch() {
        assertEquals(
            "https://other.example:8443",
            assertIs<InviteInput.OtherOrigin>(parse("https://other.example:8443/invite#$code")).origin,
        )
    }

    @Test
    fun everythingElseIsInvalid() {
        val cases =
            listOf(
                "",
                "$origin/invite#${code}A", // 23 characters
                "$origin/invite#${code.dropLast(1)}", // 21
                "$origin/invite#${code.dropLast(1)}B", // non-canonical final bits
                "$origin/invite?code=$code", // query parameter
                "$origin/invite#$code#$code", // double fragment
                "$origin/invite#$code/", // trailing path
                "$origin/invite/#$code",
                "$origin/INVITE#$code",
                "${origin.uppercase()}/invite#$code", // uppercase host
                "https://GifItUp.example/invite#$code",
                "https://real.example@evil.example/invite#$code", // user info
                "https://evil.example\\@real.example/invite#$code", // backslash
                "https://gifitup.example:443/invite#$code", // default port written out
                "http://gifitup.example/invite#$code",
                "gifitup.example/invite#$code",
                "$origin/invite#$code​", // zero-width space
                "​$origin/invite#$code",
                "$code​",
                "$code $code",
                "javascript:alert(1)//$origin/invite#$code",
                "$origin /invite#$code",
            )
        cases.forEach { assertIs<InviteInput.Invalid>(parse(it), "refused: $it") }
    }

    @Test
    fun theCodeNeverPrints() {
        val parsed = assertIs<InviteInput.Code>(parse(code))
        assertFalse(code in parsed.code.toString())
        assertFalse(code in "${InviteInput.Code(parsed.code)}".plus(parsed.code))
        assertNotNull(InviteCode.of(code))
        assertTrue(InviteCode.of(code) == parsed.code)
    }

    @Test
    fun buildRefusesANonCanonicalOrigin() {
        val code = assertIs<InviteInput.Code>(parse(code)).code
        listOf("http://x.example", "https://X.example", "https://x.example/").forEach {
            assertTrue(runCatching { InviteLinks.build(it, code) }.isFailure, it)
        }
    }
}
