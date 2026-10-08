package com.bitchat.watch.notification

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WearNotificationLaunchGuardTest {

    @Test
    fun `only the per-process token is trusted`() {
        assertTrue(WearNotificationLaunchGuard.isTrustedToken(WearNotificationLaunchGuard.currentTokenForTest()))
        assertFalse(WearNotificationLaunchGuard.isTrustedToken(null))
        assertFalse(WearNotificationLaunchGuard.isTrustedToken(""))
        assertFalse(WearNotificationLaunchGuard.isTrustedToken("0".repeat(64)))
    }

    @Test
    fun `peer id must be 16 hex characters`() {
        assertTrue(WearNotificationLaunchGuard.isValidPeerID("0123456789abcdef"))
        assertTrue(WearNotificationLaunchGuard.isValidPeerID("0123456789ABCDEF"))
        assertFalse(WearNotificationLaunchGuard.isValidPeerID(null))
        assertFalse(WearNotificationLaunchGuard.isValidPeerID(""))
        assertFalse(WearNotificationLaunchGuard.isValidPeerID("0123456789abcde"))
        assertFalse(WearNotificationLaunchGuard.isValidPeerID("0123456789abcdef0"))
        assertFalse(WearNotificationLaunchGuard.isValidPeerID("0123456789abcdeg"))
    }
}
