package com.bitchat.android.noise

import com.bitchat.android.noise.NoiseSession.Companion.REPLAY_WINDOW_BYTES
import com.bitchat.android.noise.NoiseSession.Companion.REPLAY_WINDOW_SIZE
import com.bitchat.android.noise.NoiseSession.Companion.isValidNonce
import com.bitchat.android.noise.NoiseSession.Companion.markNonceAsSeen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoiseReplayWindowTest {

    private var highest = 0L
    private var window = ByteArray(REPLAY_WINDOW_BYTES)

    private fun receive(nonce: Long): Boolean {
        if (!isValidNonce(nonce, highest, window)) return false
        val (newHighest, newWindow) = markNonceAsSeen(nonce, highest, window)
        highest = newHighest
        window = newWindow
        return true
    }

    @Test
    fun `replay of previous nonce is rejected after window advances by one`() {
        assertTrue(receive(1))
        assertTrue(receive(2))
        assertFalse(receive(1))
        assertFalse(receive(2))
    }

    @Test
    fun `every seen nonce stays rejected across sequential delivery`() {
        for (n in 1L..300L) assertTrue("nonce $n", receive(n))
        for (n in 1L..300L) assertFalse("replay $n", receive(n))
    }

    @Test
    fun `seen nonces stay rejected across shifts that are not byte aligned`() {
        val seen = mutableListOf<Long>()
        var n = 5L
        for (step in listOf(3L, 1L, 7L, 9L, 13L, 8L, 16L, 17L, 2L, 31L, 100L, 255L)) {
            n += step
            assertTrue("nonce $n", receive(n))
            seen += n
            for (s in seen) {
                if (s + REPLAY_WINDOW_SIZE > highest) assertFalse("replay $s after $n", receive(s))
            }
        }
    }

    @Test
    fun `out of order nonces are accepted once and then rejected`() {
        assertTrue(receive(10))
        assertTrue(receive(7))
        assertTrue(receive(9))
        assertTrue(receive(20))
        assertFalse(receive(7))
        assertFalse(receive(9))
        assertFalse(receive(10))
        assertTrue(receive(8))
        assertFalse(receive(8))
        assertTrue(receive(19))
    }

    @Test
    fun `unseen nonces inside the window are still accepted after a shift`() {
        assertTrue(receive(100))
        assertTrue(receive(103))
        assertTrue(receive(101))
        assertTrue(receive(102))
        assertTrue(receive(99))
    }

    @Test
    fun `window edge and large jumps`() {
        assertTrue(receive(1))
        assertTrue(receive(REPLAY_WINDOW_SIZE.toLong()))
        assertFalse(receive(1))
        assertTrue(receive(1L + REPLAY_WINDOW_SIZE * 3))
        assertFalse(receive(1L + REPLAY_WINDOW_SIZE * 3))
        assertFalse(receive(REPLAY_WINDOW_SIZE.toLong()))
        assertTrue(receive(2L + REPLAY_WINDOW_SIZE * 2))
    }

    @Test
    fun `jump larger than Int range clears the window`() {
        assertTrue(receive(5))
        assertTrue(receive(0xFFFFFFFFL))
        assertEquals(1, window[0].toInt() and 0xFF)
        for (i in 1 until REPLAY_WINDOW_BYTES) assertEquals(0, window[i].toInt())
        assertFalse(receive(5))
        assertFalse(receive(0xFFFFFFFFL))
        assertTrue(receive(0xFFFFFFFEL))
    }

    @Test
    fun `shift keeps the bit layout offset equals distance from highest`() {
        val (h1, w1) = markNonceAsSeen(50, 0, ByteArray(REPLAY_WINDOW_BYTES))
        val (h2, w2) = markNonceAsSeen(61, h1, w1)
        assertEquals(61L, h2)
        assertEquals(1, w2[0].toInt() and 0xFF)
        // nonce 50 is offset 11 -> byte 1, bit 3
        assertEquals(1 shl 3, w2[1].toInt() and 0xFF)
    }
}
