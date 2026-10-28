package app.gifitup.shared.domain.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class FrameCacheTest {
    private val released = ArrayList<String>()
    private val cache = FrameCache<String>(100) { released += it }

    @Test
    fun framesAreKeptUpToTheLimitAndFoundAgain() {
        assertSame("a", cache.put(0, "a", 60))
        assertEquals("a", cache.get(0))
        cache.put(1, "b", 41) // over the limit: shown, not kept
        assertNull(cache.get(1))
        cache.put(2, "c", 40)
        assertEquals("c", cache.get(2))
        assertEquals(emptyList(), released)
    }

    @Test
    fun anUncachedFrameIsReleasedWhenTheNextUncachedOneReplacesIt() {
        cache.put(0, "big", 101)
        cache.put(1, "big2", 101)
        assertEquals(listOf("big"), released)
    }

    @Test
    fun closeReleasesEverythingOnceAndASecondCloseReleasesNothing() {
        cache.put(0, "a", 10)
        cache.put(1, "b", 10)
        cache.put(2, "u", 500)
        cache.close()
        assertEquals(setOf("a", "b", "u"), released.toSet())
        assertEquals(3, released.size)
        cache.close()
        assertEquals(3, released.size)
        assertNull(cache.get(0))
    }

    @Test
    fun aHugeOrNegativeSizeIsNeverKeptAndNothingOverflows() {
        cache.put(0, "x", Int.MAX_VALUE)
        cache.put(1, "y", -1)
        assertNull(cache.get(0))
        assertNull(cache.get(1))
        cache.put(2, "z", 100)
        assertEquals("z", cache.get(2))
    }

    @Test
    fun aFrameForAnIndexAlreadyKeptIsNotKeptTwice() {
        cache.put(0, "a", 10)
        cache.put(0, "again", 10)
        assertEquals("a", cache.get(0))
        cache.close()
        assertEquals(setOf("a", "again"), released.toSet())
    }
}
