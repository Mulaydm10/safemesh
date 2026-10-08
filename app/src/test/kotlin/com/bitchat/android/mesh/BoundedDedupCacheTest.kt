package com.bitchat.android.mesh

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedDedupCacheTest {

    @Test
    fun `size is capped on insert by evicting the oldest entry`() {
        val cache = BoundedDedupCache(maxEntries = 3)
        (1..5).forEach { cache.record("k$it", it.toLong()) }

        assertEquals(3, cache.size())
        assertFalse(cache.contains("k1"))
        assertFalse(cache.contains("k2"))
        assertTrue(cache.contains("k5"))
    }

    @Test
    fun `re-recording a key protects it from the next eviction`() {
        val cache = BoundedDedupCache(maxEntries = 2)
        cache.record("a", 1)
        cache.record("b", 2)
        cache.record("a", 3)
        cache.record("c", 4)

        assertTrue(cache.contains("a"))
        assertFalse(cache.contains("b"))
    }

    @Test
    fun `removeOlderThan drops only expired entries`() {
        val cache = BoundedDedupCache(maxEntries = 10)
        cache.record("old", 100)
        cache.record("new", 200)

        cache.removeOlderThan(150)

        assertFalse(cache.contains("old"))
        assertTrue(cache.contains("new"))
    }

    @Test
    fun `concurrent inserts during cleanup do not throw and stay bounded`() {
        val cap = 1_000
        val cache = BoundedDedupCache(cap)
        val writers = 4
        val pool = Executors.newFixedThreadPool(writers + 1)
        val start = CountDownLatch(1)
        val failures = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()

        repeat(writers) { w ->
            pool.execute {
                start.await()
                try {
                    repeat(50_000) { i -> cache.record("w$w-$i", i.toLong()) }
                } catch (t: Throwable) {
                    failures.add(t)
                }
            }
        }
        pool.execute {
            start.await()
            try {
                repeat(2_000) { i ->
                    cache.removeOlderThan(i.toLong())
                    cache.keys()
                }
            } catch (t: Throwable) {
                failures.add(t)
            }
        }
        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS))

        assertTrue("Unexpected failures: $failures", failures.isEmpty())
        assertTrue(cache.size() <= cap)
    }
}
