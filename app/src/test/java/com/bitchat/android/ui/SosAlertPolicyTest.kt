package com.bitchat.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SosAlertPolicyTest {
    private var now = 0L
    private val policy = SosAlertPolicy(
        perSenderCooldownMs = 1_000L,
        untrustedWindowMs = 10_000L,
        maxUntrustedPerWindow = 2,
        clock = { now }
    )

    @Test
    fun repeatSosFromSameSenderIsSuppressedUntilCooldownAndReusesId() {
        val first = policy.evaluate("alice", trusted = true)
        assertNotNull(first)
        assertNull(policy.evaluate("alice", trusted = true))
        now = 1_000L
        val second = policy.evaluate("alice", trusted = true)
        assertEquals(first, second)
    }

    @Test
    fun untrustedSendersAreCappedGloballyButTrustedStillAlert() {
        assertNotNull(policy.evaluate("fresh-1", trusted = false))
        assertNotNull(policy.evaluate("fresh-2", trusted = false))
        assertNull(policy.evaluate("fresh-3", trusted = false))
        val trusted = policy.evaluate("friend", trusted = true)
        assertTrue(trusted!!.trusted)
        now = 10_000L
        assertNotNull(policy.evaluate("fresh-4", trusted = false))
    }

    @Test
    fun untrustedNotificationIdsComeFromABoundedPool() {
        val ids = (0 until 50).mapNotNull { i ->
            now = i * 10_000L
            policy.evaluate("fresh-$i", trusted = false)?.notificationId
        }.toSet()
        assertTrue(ids.size <= 2)
    }
}
