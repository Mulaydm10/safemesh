package com.bitchat.android.services

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class VerificationServiceParseTest {

    private fun tlv(type: Int, value: ByteArray): ByteArray =
        byteArrayOf(type.toByte(), value.size.toByte()) + value

    @Test
    fun challengeWithLengthAbove127IsParsed() {
        val noise = "a".repeat(200)
        val nonce = ByteArray(130) { it.toByte() }
        val data = VerificationService.buildVerifyChallenge(noise, nonce)

        val parsed = VerificationService.parseVerifyChallenge(data)
        assertNotNull(parsed)
        parsed!!
        assertEquals(noise, parsed.first)
        assertArrayEquals(nonce, parsed.second)
    }

    @Test
    fun challengeWithHighLengthByteAndShortPayloadReturnsNull() {
        val data = byteArrayOf(0x01, 0x80.toByte(), 0x41)
        assertNull(VerificationService.parseVerifyChallenge(data))
    }

    @Test
    fun challengeWithMaxLengthByteAndShortPayloadReturnsNull() {
        val data = byteArrayOf(0x01, 0x01, 0x41, 0x02, 0xFF.toByte(), 0x00)
        assertNull(VerificationService.parseVerifyChallenge(data))
    }

    @Test
    fun responseWithLengthsAbove127IsParsed() {
        val noise = ByteArray(150) { 'b'.code.toByte() }
        val nonce = ByteArray(128) { (it * 3).toByte() }
        val sig = ByteArray(255) { (it + 1).toByte() }
        val data = tlv(0x01, noise) + tlv(0x02, nonce) + tlv(0x03, sig)

        val parsed = VerificationService.parseVerifyResponse(data)!!
        assertEquals(String(noise, Charsets.UTF_8), parsed.noiseKeyHex)
        assertArrayEquals(nonce, parsed.nonceA)
        assertArrayEquals(sig, parsed.signature)
    }

    @Test
    fun responseWithHighLengthBytesAndShortPayloadReturnsNull() {
        val data = byteArrayOf(0x01, 0x01, 0x41, 0x02, 0x01, 0x00, 0x03, 0x90.toByte(), 0x01)
        assertNull(VerificationService.parseVerifyResponse(data))
        assertNull(VerificationService.parseVerifyResponse(byteArrayOf(0x01, 0xC0.toByte())))
    }
}
