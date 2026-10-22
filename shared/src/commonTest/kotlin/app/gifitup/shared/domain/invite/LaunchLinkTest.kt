package app.gifitup.shared.domain.invite

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LaunchLinkTest {
    private val link = "https://gifitup.test/invite#AAAAAAAAAAAAAAAAAAAAAQ"

    @Test
    fun aLinkFromAFreshViewLaunchIsACandidate() {
        assertEquals(link, LaunchLink.candidate(isView = true, data = link, freshLaunch = true))
    }

    @Test
    fun nothingIsTakenFromARotationOrARestore() {
        assertNull(LaunchLink.candidate(isView = true, data = link, freshLaunch = false))
    }

    @Test
    fun onlyTheUsersOwnOpeningCounts() {
        assertNull(LaunchLink.candidate(isView = false, data = link, freshLaunch = true), "an intent that isn't a VIEW")
    }

    @Test
    fun onlyBoundedHttpsLinksPass() {
        listOf(
            null,
            "",
            "http://gifitup.test/invite#x",
            "content://x/invite",
            "javascript:alert(1)",
            link.padEnd(LaunchLink.MAX_LENGTH + 1, 'A'),
        ).forEach { assertNull(LaunchLink.candidate(isView = true, data = it, freshLaunch = true), "$it") }
        assertEquals(
            link.padEnd(LaunchLink.MAX_LENGTH, 'A'),
            LaunchLink.candidate(true, link.padEnd(LaunchLink.MAX_LENGTH, 'A'), true),
            "the limit itself passes; the strict parser refuses it later",
        )
    }
}
