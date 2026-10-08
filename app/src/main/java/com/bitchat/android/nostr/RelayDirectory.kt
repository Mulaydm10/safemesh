package com.bitchat.android.nostr

import android.app.Application
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import kotlin.math.*

/**
 * Loads relay coordinates from the bundled asset and provides nearest-relay lookup by geohash.
 *
 * The list is never fetched at runtime: SafeMesh is offline-only, and a remote list could steer
 * geohash traffic to arbitrary relays. Only `wss://` relays with valid hostnames are accepted.
 */
object RelayDirectory {

    private const val TAG = "RelayDirectory"
    private const val ASSET_FILE = "nostr_relays.csv"
    private const val LEGACY_DOWNLOADED_FILE = "nostr_relays_latest.csv"
    private const val LEGACY_PREFS_NAME = "relay_directory_prefs"
    internal const val MAX_ENTRIES = 5000
    private const val MAX_LINE_LENGTH = 512

    private val HOST_LABEL = Regex("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$")

    data class RelayInfo(
        val url: String,
        val latitude: Double,
        val longitude: Double
    )

    @Volatile
    private var initialized: Boolean = false

    private val relays: MutableList<RelayInfo> = mutableListOf()
    private val relaysLock = Any()

    fun initialize(application: Application) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            try {
                discardLegacyDownload(application)
                loadFromAssets(application)
                initialized = true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize RelayDirectory: ${e.message}")
            }
        }
    }

    /**
     * Return up to nRelays closest relay URLs to the geohash center.
     */
    fun closestRelaysForGeohash(geohash: String, nRelays: Int): List<String> {
        val snapshot = synchronized(relaysLock) { relays.toList() }
        if (snapshot.isEmpty()) return emptyList()
        val center = try {
            com.bitchat.android.geohash.Geohash.decodeToCenter(geohash)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode geohash")
            return emptyList()
        }

        val (lat, lon) = center
        return snapshot
            .asSequence()
            .sortedBy { haversineMeters(lat, lon, it.latitude, it.longitude) }
            .take(nRelays.coerceAtLeast(0))
            .map { it.url }
            .toList()
    }

    private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371000.0 // meters
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2.0) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2.0)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return R * c
    }

    /**
     * Returns `wss://host[:port]` for a bare hostname or a `wss://` URL, or null for any other
     * scheme, a path/query/userinfo, or an invalid hostname or port.
     */
    internal fun normalizeRelayUrl(raw: String): String? {
        var rest = raw.trim().lowercase()
        if (rest.isEmpty()) return null
        if ("://" in rest) {
            if (!rest.startsWith("wss://")) return null
            rest = rest.removePrefix("wss://")
        }
        rest = rest.removeSuffix("/")

        val host: String
        val port: Int?
        val colon = rest.lastIndexOf(':')
        if (colon >= 0) {
            host = rest.substring(0, colon)
            port = rest.substring(colon + 1).toIntOrNull() ?: return null
            if (port !in 1..65535) return null
        } else {
            host = rest
            port = null
        }

        if (!isValidHostname(host)) return null
        return if (port == null) "wss://$host" else "wss://$host:$port"
    }

    private fun isValidHostname(host: String): Boolean {
        if (host.isEmpty() || host.length > 253) return false
        val labels = host.split('.')
        if (labels.size < 2) return false
        if (labels.all { it.all(Char::isDigit) }) return false
        return labels.all { HOST_LABEL.matches(it) } && !labels.last().all(Char::isDigit)
    }

    // ===== Implementation details =====

    /** Earlier builds downloaded the list at runtime; drop any cached copy so it is never used. */
    private fun discardLegacyDownload(application: Application) {
        try {
            File(application.filesDir, LEGACY_DOWNLOADED_FILE).delete()
            application.deleteSharedPreferences(LEGACY_PREFS_NAME)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to remove legacy relay download: ${e.message}")
        }
    }

    private fun loadFromAssets(application: Application) {
        val list = try {
            parseCsv(application.assets.open(ASSET_FILE))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open asset $ASSET_FILE: ${e.message}")
            emptyList()
        }
        synchronized(relaysLock) {
            relays.clear()
            relays.addAll(list)
        }
        Log.i(TAG, "Loaded ${list.size} relay entries from assets/$ASSET_FILE")
    }

    internal fun parseCsv(input: InputStream): List<RelayInfo> {
        val result = mutableListOf<RelayInfo>()
        val seen = HashSet<String>()
        BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { reader ->
            while (result.size < MAX_ENTRIES) {
                val line = reader.readLine() ?: break
                if (line.length > MAX_LINE_LENGTH) continue
                val trimmed = line.trim()
                if (trimmed.isEmpty()) continue
                if (trimmed.lowercase().startsWith("relay url")) continue
                val parts = trimmed.split(",")
                if (parts.size < 3) continue
                val url = normalizeRelayUrl(parts[0]) ?: continue
                val lat = parts[1].trim().toDoubleOrNull() ?: continue
                val lon = parts[2].trim().toDoubleOrNull() ?: continue
                if (lat !in -90.0..90.0 || lon !in -180.0..180.0) continue
                if (!seen.add(url)) continue
                result.add(RelayInfo(url = url, latitude = lat, longitude = lon))
            }
        }
        return result
    }
}
