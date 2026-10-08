package com.bitchat.android.nostr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class RelayDirectoryTest {
    @Test
    fun `bare hosts and wss urls are normalized`() {
        assertEquals("wss://relay.example.org", RelayDirectory.normalizeRelayUrl(" relay.example.org "))
        assertEquals("wss://relay.example.org", RelayDirectory.normalizeRelayUrl("WSS://Relay.Example.org/"))
        assertEquals("wss://relay.example.org:7447", RelayDirectory.normalizeRelayUrl("wss://relay.example.org:7447"))
    }

    @Test
    fun `non wss schemes and malformed hosts are rejected`() {
        listOf(
            "ws://relay.example.org",
            "http://relay.example.org",
            "file:///etc/hosts",
            "wss://user@relay.example.org",
            "wss://relay.example.org/path",
            "wss://relay.example.org:0",
            "wss://relay.example.org:99999",
            "wss://127.0.0.1",
            "localhost",
            "relay..example.org",
            "-relay.example.org",
            "relay_x.example.org",
            "",
        ).forEach { assertNull(it, RelayDirectory.normalizeRelayUrl(it)) }
    }

    @Test
    fun `parser skips invalid rows, duplicates and out of range coordinates`() {
        val csv = """
            Relay URL,Latitude,Longitude
            relay.example.org,10.0,20.0
            ws://plain.example.org,10.0,20.0
            relay.example.org,11.0,21.0
            far.example.org,91.0,0.0
            other.example.net,-10.5,170.25
        """.trimIndent()
        val parsed = RelayDirectory.parseCsv(csv.byteInputStream())
        assertEquals(
            listOf(
                RelayDirectory.RelayInfo("wss://relay.example.org", 10.0, 20.0),
                RelayDirectory.RelayInfo("wss://other.example.net", -10.5, 170.25),
            ),
            parsed,
        )
    }

    @Test
    fun `parser caps the number of entries`() {
        val csv = (0 until RelayDirectory.MAX_ENTRIES + 10).joinToString("\n") { "r$it.example.org,0.0,0.0" }
        assertEquals(RelayDirectory.MAX_ENTRIES, RelayDirectory.parseCsv(csv.byteInputStream()).size)
    }

    @Test
    fun `bundled relay list parses fully`() {
        val asset = File("src/main/assets/nostr_relays.csv")
        val dataRows = asset.readLines().count { it.isNotBlank() && !it.lowercase().startsWith("relay url") }
        assertEquals(dataRows, RelayDirectory.parseCsv(asset.inputStream()).size)
    }
}
