package com.bitchat.android.services

import com.bitchat.android.util.AppConstants
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VerificationServiceFreshnessTest {
    private fun now() = System.currentTimeMillis() / 1000L

    @Test
    fun acceptsRecentTimestamp() {
        assertTrue(VerificationService.isFresh(now() - 10))
    }

    @Test
    fun rejectsExpiredTimestamp() {
        assertFalse(VerificationService.isFresh(now() - AppConstants.Verification.QR_MAX_AGE_SECONDS - 5))
    }

    @Test
    fun allowsSmallClockSkew() {
        assertTrue(VerificationService.isFresh(now() + 5))
    }

    @Test
    fun rejectsFutureTimestamp() {
        assertFalse(VerificationService.isFresh(now() + AppConstants.Verification.QR_MAX_FUTURE_SKEW_SECONDS + 5))
        assertFalse(VerificationService.isFresh(Long.MAX_VALUE / 2))
    }
}
