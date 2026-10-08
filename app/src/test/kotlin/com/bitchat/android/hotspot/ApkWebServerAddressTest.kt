package com.bitchat.android.hotspot

import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApkWebServerAddressTest {

    private val groupOwner = InetAddress.getByName("192.168.49.1")

    @Test
    fun `group clients are inside the subnet`() {
        assertTrue(ApkWebServer.isInSubnet(InetAddress.getByName("192.168.49.37"), groupOwner, 24))
    }

    @Test
    fun `hosts on other networks are outside the subnet`() {
        assertFalse(ApkWebServer.isInSubnet(InetAddress.getByName("192.168.1.20"), groupOwner, 24))
        assertFalse(ApkWebServer.isInSubnet(InetAddress.getByName("10.0.0.5"), groupOwner, 24))
    }

    @Test
    fun `non byte aligned prefixes are respected`() {
        assertTrue(ApkWebServer.isInSubnet(InetAddress.getByName("192.168.49.20"), groupOwner, 27))
        assertFalse(ApkWebServer.isInSubnet(InetAddress.getByName("192.168.49.40"), groupOwner, 27))
    }

    @Test
    fun `ipv6 clients never match an ipv4 group`() {
        assertFalse(ApkWebServer.isInSubnet(InetAddress.getByName("2001:db8::1"), groupOwner, 24))
    }

    @Test
    fun `bind address must be a concrete ipv4 literal`() {
        assertEquals("192.168.49.1", ApkWebServer.requireIpv4Literal("192.168.49.1"))
        assertThrowsIllegalArgument { ApkWebServer.requireIpv4Literal("") }
        assertThrowsIllegalArgument { ApkWebServer.requireIpv4Literal("::") }
        assertThrowsIllegalArgument { ApkWebServer.requireIpv4Literal("0.0.0.0") }
        assertThrowsIllegalArgument { ApkWebServer.requireIpv4Literal("localhost") }
        assertThrowsIllegalArgument { ApkWebServer.requireIpv4Literal("192.168.49.256") }
    }

    private fun assertThrowsIllegalArgument(block: () -> Unit) {
        try {
            block()
        } catch (e: IllegalArgumentException) {
            return
        }
        throw AssertionError("Expected IllegalArgumentException")
    }
}
