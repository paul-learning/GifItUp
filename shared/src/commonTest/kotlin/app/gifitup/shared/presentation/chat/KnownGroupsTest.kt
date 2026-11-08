package app.gifitup.shared.presentation.chat

import app.gifitup.shared.domain.contacts.KeyBytes
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KnownGroupsTest {
    private fun id(n: Int) = KeyBytes(byteArrayOf(n.toByte()))

    @Test
    fun aHostileStreamOfUnknownIdsListsTheGroupsOncePerWindow() =
        runTest {
            var loads = 0
            var now = 0L
            val known = KnownGroups({ loads++.let { listOf(id(1)) } }, { now })
            repeat(1_000) { assertFalse(known.contains(id(100 + it % 50))) }
            assertEquals(1, loads, "one listing for a thousand unknown ids in the same instant")
            now = 6_000
            assertFalse(known.contains(id(99)))
            assertEquals(2, loads)
        }

    @Test
    fun aKnownIdCostsNothingAndAForgottenSnapshotReloads() =
        runTest {
            var loads = 0
            var groups = listOf(id(1))
            val known = KnownGroups({ loads++.let { groups } }, { 0L })
            assertTrue(known.contains(id(1)))
            repeat(100) { assertTrue(known.contains(id(1))) }
            assertEquals(1, loads)
            groups = listOf(id(1), id(2))
            assertFalse(known.contains(id(2)), "a new group is unknown until the snapshot is reloaded")
            known.forget()
            assertTrue(known.contains(id(2)))
            assertEquals(listOf(id(1), id(2)), known.all())
        }
}
