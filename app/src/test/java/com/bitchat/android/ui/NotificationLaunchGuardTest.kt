package com.bitchat.android.ui

import com.bitchat.android.geohash.Geohash
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationLaunchGuardTest {

    @Test
    fun `only the per-process token is trusted`() {
        assertTrue(NotificationLaunchGuard.isTrustedToken(NotificationLaunchGuard.currentTokenForTest()))
        assertFalse(NotificationLaunchGuard.isTrustedToken(null))
        assertFalse(NotificationLaunchGuard.isTrustedToken(""))
        assertFalse(NotificationLaunchGuard.isTrustedToken("0".repeat(64)))
    }

    @Test
    fun `channel geohash must be base32 with a channel precision`() {
        listOf("u0", "u0b1", "u0b1c", "u0b1cd", "u0b1cde", "u0b1cdef").forEach {
            assertTrue(it, Geohash.isValidChannelGeohash(it))
        }
        listOf("", "u", "u0b", "u0b1cdefg", "U0B1C", "u0a1c", "u0i1c", "u0l1c", "u0o1c", "u0/1c", "u0 1c").forEach {
            assertFalse(it, Geohash.isValidChannelGeohash(it))
        }
    }
}
