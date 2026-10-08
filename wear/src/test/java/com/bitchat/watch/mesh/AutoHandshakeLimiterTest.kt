package com.bitchat.watch.mesh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoHandshakeLimiterTest {

    @Test
    fun `same peer is throttled until its cooldown elapses`() {
        val limiter = AutoHandshakeLimiter(perPeerCooldownMs = 1_000, maxPerWindow = 10)
        assertTrue(limiter.tryAcquire("peer-a", nowMs = 0))
        assertFalse(limiter.tryAcquire("peer-a", nowMs = 999))
        assertTrue(limiter.tryAcquire("peer-a", nowMs = 1_000))
    }

    @Test
    fun `fresh identities share one global budget per window`() {
        val limiter = AutoHandshakeLimiter(globalWindowMs = 1_000, maxPerWindow = 3)
        assertTrue(limiter.tryAcquire("peer-1", nowMs = 0))
        assertTrue(limiter.tryAcquire("peer-2", nowMs = 1))
        assertTrue(limiter.tryAcquire("peer-3", nowMs = 2))
        assertFalse(limiter.tryAcquire("peer-4", nowMs = 3))
        assertTrue(limiter.tryAcquire("peer-4", nowMs = 1_000))
    }

    @Test
    fun `tracked peers stay bounded under identity churn`() {
        val limiter = AutoHandshakeLimiter(
            perPeerCooldownMs = Long.MAX_VALUE,
            maxPerWindow = Int.MAX_VALUE,
            maxTrackedPeers = 4
        )
        repeat(1_000) { limiter.tryAcquire("peer-$it", nowMs = it.toLong()) }
        assertEquals(4, limiter.trackedPeerCount())
    }

    @Test
    fun `expired entries are pruned`() {
        val limiter = AutoHandshakeLimiter(perPeerCooldownMs = 100, maxPerWindow = 10)
        repeat(5) { limiter.tryAcquire("peer-$it", nowMs = 0) }
        limiter.tryAcquire("peer-new", nowMs = 100)
        assertEquals(1, limiter.trackedPeerCount())
    }

    @Test
    fun `forgotten peer can be retried immediately`() {
        val limiter = AutoHandshakeLimiter(perPeerCooldownMs = 1_000, maxPerWindow = 10)
        assertTrue(limiter.tryAcquire("peer-a", nowMs = 0))
        limiter.forget("peer-a")
        assertTrue(limiter.tryAcquire("peer-a", nowMs = 1))
    }
}
