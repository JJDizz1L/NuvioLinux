package com.nuviolinux.app.core.storage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BoundedLruCacheTest {

    /** Controllable monotonic clock for deterministic expiry tests. */
    private class FakeClock {
        var now: Long = 0L
        fun tick(millis: Long) {
            now += millis
        }
    }

    @Test
    fun `stores and retrieves values`() {
        val cache = BoundedLruCache<String, Int>(maxSize = 4)
        cache.put("a", 1)
        assertEquals(1, cache.get("a"))
        assertNull(cache.get("missing"))
        assertEquals(1, cache.size)
    }

    @Test
    fun `evicts least-recently-used entry over capacity`() {
        val cache = BoundedLruCache<String, Int>(maxSize = 2)
        cache.put("a", 1)
        cache.put("b", 2)
        // Touch "a" so "b" becomes the LRU entry.
        assertEquals(1, cache.get("a"))
        cache.put("c", 3)

        assertEquals(1, cache.get("a"))
        assertNull(cache.get("b"))
        assertEquals(3, cache.get("c"))
        assertEquals(2, cache.size)
    }

    @Test
    fun `put on existing key updates value without evicting`() {
        val cache = BoundedLruCache<String, Int>(maxSize = 2)
        cache.put("a", 1)
        cache.put("b", 2)
        cache.put("a", 10)
        cache.put("c", 3) /* LRU is "b" ("a" was refreshed by its put) */

        assertEquals(10, cache.get("a"))
        assertNull(cache.get("b"))
        assertEquals(3, cache.get("c"))
    }

    @Test
    fun `entries older than maxAge expire`() {
        val clock = FakeClock()
        val cache = BoundedLruCache<String, Int>(
            maxSize = 8,
            maxAge = 100.millisForTest(),
            nowMillis = { clock.now },
        )
        cache.put("a", 1)

        clock.tick(50)
        assertEquals(1, cache.get("a"))

        clock.tick(60) /* created 110ms ago > maxAge */
        assertNull(cache.get("a"))
        assertEquals(0, cache.size)
    }

    @Test
    fun `unutilized entries expire after idleTtl even when recently created`() {
        val clock = FakeClock()
        val cache = BoundedLruCache<String, Int>(
            maxSize = 8,
            idleTtl = 40.millisForTest(),
            nowMillis = { clock.now },
        )
        cache.put("a", 1)
        clock.tick(20)
        assertEquals(1, cache.get("a")) /* refreshes last-access */
        clock.tick(30) /* 30ms since access < 40 */
        assertEquals(1, cache.get("a"))
        clock.tick(41) /* idle beyond ttl -> dropped */
        assertNull(cache.get("a"))
    }

    @Test
    fun `periodic sweep removes cold entries while under capacity`() {
        val clock = FakeClock()
        val cache = BoundedLruCache<String, Int>(
            maxSize = 100,
            idleTtl = 10.millisForTest(),
            sweepEvery = 4,
            nowMillis = { clock.now },
        )
        repeat(4) { index ->
            cache.put("k$index", index)
            clock.tick(1)
        }
        /* Idle everything well past the ttl... */
        clock.tick(100)
        /* ...then cross the sweep cadence with unrelated puts. */
        repeat(4) { cache.put("cold$it", it) }
        assertTrue(cache.size <= 8)
        for (index in 0 until 4) {
            assertNull(cache.get("k$index"), "cold entry k$index should have been swept")
        }
    }

    @Test
    fun `getOrPut loads once and caches`() {
        var loads = 0
        val cache = BoundedLruCache<String, String>(maxSize = 4)
        val first = cache.getOrPut("x") { loads++; "loaded" }
        val second = cache.getOrPut("x") { loads++; "should-not-run" }
        assertEquals("loaded", first)
        assertEquals("loaded", second)
        assertEquals(1, loads)
    }

    @Test
    fun `remove and clear empty the cache`() {
        val cache = BoundedLruCache<String, Int>(maxSize = 4)
        cache.put("a", 1)
        cache.put("b", 2)
        cache.remove("a")
        assertNull(cache.get("a"))
        assertFalse(cache.remove("missing"))
        cache.clear()
        assertEquals(0, cache.size)
        assertNull(cache.get("b"))
    }

    private fun Int.millisForTest(): kotlin.time.Duration = kotlin.time.Duration.parse("${this}ms")
}
