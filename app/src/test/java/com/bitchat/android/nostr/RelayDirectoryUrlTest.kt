package com.bitchat.android.nostr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RelayDirectoryUrlTest {

    @Test
    fun acceptsBareAndWssHosts() {
        assertEquals("wss://relay.example.com", RelayDirectory.normalizeRelayUrl(" relay.example.com "))
        assertEquals("wss://nostr.example.org:443", RelayDirectory.normalizeRelayUrl("nostr.example.org:443"))
        assertEquals("wss://relay.example.net", RelayDirectory.normalizeRelayUrl("WSS://Relay.Example.net"))
    }

    @Test
    fun rejectsPlaintextAndOtherSchemes() {
        listOf("ws://relay.example.com", "http://relay.example.com", "https://relay.example.com").forEach {
            assertNull(it, RelayDirectory.normalizeRelayUrl(it))
        }
    }

    @Test
    fun rejectsIpLiteralsLocalNamesAndPaths() {
        listOf(
            "192.0.2.1", "wss://10.0.0.1:7777", "wss://[::1]", "localhost", "relay.local",
            "box.internal", "router", "relay.example.com/path", "user@relay.example.com",
            "relay.example.com:0", "relay.example.com:99999", ""
        ).forEach { assertNull(it, RelayDirectory.normalizeRelayUrl(it)) }
    }
}
