package com.bitchat.android.mesh

import android.util.Log
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import com.bitchat.android.protocol.MessagePadding
import com.bitchat.android.model.FragmentPayload
import kotlinx.coroutines.*
import com.bitchat.android.util.AppConstants
import com.bitchat.android.util.toHexString

/**
 * Manages message fragmentation and reassembly - 100% iOS Compatible
 * 
 * This implementation exactly matches iOS SimplifiedBluetoothService fragmentation:
 * - Same fragment payload structure (13-byte header + data)
 * - Same MTU thresholds and fragment sizes
 * - Same reassembly logic and timeout handling
 * - Uses new FragmentPayload model for type safety
 */
class FragmentManager {
    
    companion object {
        private const val TAG = "FragmentManager"
        const val LOCAL_LINK_KEY = "local"
        // iOS values: 512 MTU threshold, 469 max fragment size (512 MTU - headers)
        private const val FRAGMENT_SIZE_THRESHOLD = com.bitchat.android.util.AppConstants.Fragmentation.FRAGMENT_SIZE_THRESHOLD // Matches iOS: if data.count > 512
        private const val MAX_FRAGMENT_SIZE = com.bitchat.android.util.AppConstants.Fragmentation.MAX_FRAGMENT_SIZE        // Matches iOS: maxFragmentSize = 469 
        private const val FRAGMENT_TIMEOUT = com.bitchat.android.util.AppConstants.Fragmentation.FRAGMENT_TIMEOUT_MS     // Matches iOS: 30 seconds cleanup
        private const val CLEANUP_INTERVAL = com.bitchat.android.util.AppConstants.Fragmentation.CLEANUP_INTERVAL_MS     // 10 seconds cleanup check
    }
    
    /**
     * In-flight reassembly state. Sets are keyed by (senderID, fragmentID) so one sender cannot
     * address another sender's sets, and each set is charged to the ingress link that opened it
     * so a single link cannot take the whole global budget.
     */
    private class FragmentSet(
        val originalType: UByte,
        val total: Int,
        val createdAt: Long,
        val linkKey: String
    ) {
        val fragments = arrayOfNulls<ByteArray>(total)
        var receivedCount = 0
        var bytes = 0
    }

    private val fragmentSets = HashMap<String, FragmentSet>()
    private val linkActiveSets = HashMap<String, Int>()
    private val linkBufferedBytes = HashMap<String, Long>()

    private val fragmentStateLock = Any()
    private var globalBufferedBytes: Long = 0L


    // Delegate for callbacks
    var delegate: FragmentManagerDelegate? = null
    
    // Coroutines
    private val managerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    init {
        startPeriodicCleanup()
    }
    
    /**
     * Create fragments from a large packet - 100% iOS Compatible
     * Matches iOS sendFragmentedPacket() implementation exactly
     */
    /** Generic/public packets retain the full UInt16 fragment-count range. */
    fun createFragments(packet: BitchatPacket): List<BitchatPacket> =
        createFragments(packet, 0xFFFF)

    /**
     * Create a fragment plan with a caller-selected bound. Private media uses
     * 256 for cross-platform admission; generic/public traffic retains the
     * UInt16 wire limit.
     */
    fun createFragments(packet: BitchatPacket, maxFragments: Int): List<BitchatPacket> {
        try {
            if (maxFragments !in 1..0xFFFF) {
                Log.w(TAG, "Rejecting invalid outbound fragment limit: $maxFragments")
                return emptyList()
            }
            val encoded = packet.toBinaryData()
            if (encoded == null) {
                Log.e(TAG, "Failed to encode packet to binary data")
                return emptyList()
            }

            // Fragment the unpadded frame; each fragment will be encoded (and padded) independently - iOS fix
            val fullData = try {
                MessagePadding.unpad(encoded)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to unpad data: ${e.message}", e)
                return emptyList()
            }

            // iOS logic: if data.count > 512 && packet.type != MessageType.fragment.rawValue
            if (fullData.size <= FRAGMENT_SIZE_THRESHOLD) {
                return listOf(packet) // No fragmentation needed
            }

            val fragments = mutableListOf<BitchatPacket>()

            // iOS: let fragmentID = Data((0..<8).map { _ in UInt8.random(in: 0...255) })
            val fragmentID = FragmentPayload.generateFragmentID()

            // iOS: stride(from: 0, to: fullData.count, by: maxFragmentSize)
            // Calculate dynamic fragment size to fit in MTU (512)
            // Packet = Header + Sender + Recipient + Route + FragmentHeader + Payload + PaddingBuffer
            val hasRoute = packet.route != null
            val version = if (hasRoute) 2 else 1
            val headerSize = if (version == 2) 15 else 13
            val senderSize = 8
            val recipientSize = if (packet.recipientID != null) 8 else 0
            // Route: 1 byte count + 8 bytes per hop
            val routeSize = if (hasRoute) (1 + (packet.route?.size ?: 0) * 8) else 0
            val fragmentHeaderSize = 13 // FragmentPayload header
            val paddingBuffer = 16 // MessagePadding.optimalBlockSize adds 16 bytes overhead

            // 512 - Overhead
            val packetOverhead = headerSize + senderSize + recipientSize + routeSize + fragmentHeaderSize + paddingBuffer
            val maxDataSize = (512 - packetOverhead).coerceAtMost(MAX_FRAGMENT_SIZE)

            if (maxDataSize <= 0) {
                Log.e(TAG, "Calculated maxDataSize is non-positive ($maxDataSize). Route too large?")
                return emptyList()
            }

            val requiredFragments = (
                (fullData.size.toLong() + maxDataSize.toLong() - 1L) / maxDataSize.toLong()
            ).toInt()
            if (requiredFragments > maxFragments) {
                Log.w(TAG, "Rejecting outbound packet requiring $requiredFragments fragments (caller cap: $maxFragments)")
                return emptyList()
            }

            // Do not allocate chunk copies until the plan passes the hard bound.
            val fragmentChunks = stride(0, fullData.size, maxDataSize) { offset ->
                val endOffset = minOf(offset + maxDataSize, fullData.size)
                fullData.sliceArray(offset..<endOffset)
            }

            // iOS: for (index, fragment) in fragments.enumerated()
            for (index in fragmentChunks.indices) {
                val fragmentData = fragmentChunks[index]

                // Create iOS-compatible fragment payload
                val fragmentPayload = FragmentPayload(
                    fragmentID = fragmentID,
                    index = index,
                    total = fragmentChunks.size,
                    originalType = packet.type,
                    data = fragmentData
                )

                // iOS: MessageType.fragment.rawValue (single fragment type)
                // Fix: Fragments must inherit source route and use v2 if routed
                val fragmentPacket = BitchatPacket(
                    version = if (packet.route != null) 2u else 1u,
                    type = MessageType.FRAGMENT.value,
                    ttl = packet.ttl,
                    senderID = packet.senderID,
                    recipientID = packet.recipientID,
                    timestamp = packet.timestamp,
                    payload = fragmentPayload.encode(),
                    route = packet.route,
                    signature = null // iOS: signature: nil
                )

                fragments.add(fragmentPacket)
            }

            return fragments
        } catch (e: Exception) {
            Log.e(TAG, "Fragment creation failed (type=${packet.type}, payload=${packet.payload.size} bytes): ${e.message}", e)
            return emptyList()
        }
    }
    
    /**
     * Handle incoming fragment - iOS-compatible wire format.
     *
     * [ingressLinkKey] identifies the link the fragment arrived on and is used only for
     * per-link quotas; reassembly itself spans links so multi-path relay still completes.
     */
    fun handleFragment(packet: BitchatPacket, ingressLinkKey: String = LOCAL_LINK_KEY): BitchatPacket? {
        if (packet.payload.size < FragmentPayload.HEADER_SIZE) {
            Log.d(TAG, "Fragment packet too small: ${packet.payload.size}")
            return null
        }

        try {
            val fragmentPayload = FragmentPayload.decode(packet.payload)
            if (fragmentPayload == null || !fragmentPayload.isValid()) {
                Log.d(TAG, "Invalid fragment payload")
                return null
            }

            val maxFragments = AppConstants.Fragmentation.MAX_FRAGMENTS_PER_ID
            if (fragmentPayload.total > maxFragments) {
                Log.w(TAG, "Rejecting fragment with excessive total count: ${fragmentPayload.total} > $maxFragments")
                return null
            }
            if (fragmentPayload.data.size > AppConstants.Fragmentation.MAX_FRAGMENT_TOTAL_BYTES) {
                return null
            }

            val setKey = "${packet.senderID.toHexString()}:${fragmentPayload.getFragmentIDString()}"

            synchronized(fragmentStateLock) {
                var set = fragmentSets[setKey]
                if (set != null) {
                    // A mismatching or duplicate fragment never alters the existing set.
                    if (set.total != fragmentPayload.total || set.originalType != fragmentPayload.originalType) {
                        Log.w(TAG, "Dropping fragment for $setKey: inconsistent metadata")
                        return null
                    }
                    if (set.fragments[fragmentPayload.index] != null) {
                        return null
                    }
                } else {
                    if (fragmentSets.size >= AppConstants.Fragmentation.MAX_ACTIVE_FRAGMENT_SETS) {
                        Log.w(TAG, "Rejecting new fragment set $setKey: global active set cap reached")
                        return null
                    }
                    val linkSets = linkActiveSets[ingressLinkKey] ?: 0
                    if (linkSets >= AppConstants.Fragmentation.MAX_ACTIVE_FRAGMENT_SETS_PER_LINK) {
                        Log.w(TAG, "Rejecting new fragment set $setKey: per-link active set cap reached")
                        return null
                    }
                    set = FragmentSet(
                        originalType = fragmentPayload.originalType,
                        total = fragmentPayload.total,
                        createdAt = System.currentTimeMillis(),
                        linkKey = ingressLinkKey
                    )
                    fragmentSets[setKey] = set
                    linkActiveSets[ingressLinkKey] = linkSets + 1
                }

                val size = fragmentPayload.data.size
                if (set.bytes + size > AppConstants.Fragmentation.MAX_FRAGMENT_TOTAL_BYTES) {
                    Log.w(TAG, "Dropping fragment set $setKey: cumulative size exceeds per-set cap")
                    removeFragmentSetLocked(setKey)
                    return null
                }
                val linkBytes = linkBufferedBytes[set.linkKey] ?: 0L
                if (linkBytes + size > AppConstants.Fragmentation.MAX_FRAGMENT_BYTES_PER_LINK ||
                    globalBufferedBytes + size > AppConstants.Fragmentation.MAX_GLOBAL_FRAGMENT_TOTAL_BYTES
                ) {
                    Log.w(TAG, "Rejecting fragment for $setKey: buffered byte cap reached")
                    if (set.receivedCount == 0) {
                        removeFragmentSetLocked(setKey)
                    }
                    return null
                }

                set.fragments[fragmentPayload.index] = fragmentPayload.data
                set.receivedCount += 1
                set.bytes += size
                linkBufferedBytes[set.linkKey] = linkBytes + size
                globalBufferedBytes += size

                if (set.receivedCount < set.total) {
                    return null
                }

                // Complete: always release the set, whether or not it decodes.
                removeFragmentSetLocked(setKey)
                val reassembled = ByteArray(set.bytes)
                var offset = 0
                for (chunk in set.fragments) {
                    chunk!!.copyInto(reassembled, offset)
                    offset += chunk.size
                }
                val originalPacket = BitchatPacket.fromBinaryData(reassembled)
                if (originalPacket == null) {
                    Log.e(TAG, "Failed to decode reassembled packet (type=${set.originalType}, total=${set.total})")
                    return null
                }
                return originalPacket.copy(ttl = 0u.toUByte())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle fragment: ${e.message}")
        }

        return null
    }

    private fun removeFragmentSetLocked(setKey: String) {
        val set = fragmentSets.remove(setKey) ?: return
        val link = set.linkKey
        val sets = (linkActiveSets[link] ?: 1) - 1
        if (sets <= 0) linkActiveSets.remove(link) else linkActiveSets[link] = sets
        val bytes = (linkBufferedBytes[link] ?: 0L) - set.bytes
        if (bytes <= 0L) linkBufferedBytes.remove(link) else linkBufferedBytes[link] = bytes
        globalBufferedBytes = (globalBufferedBytes - set.bytes).coerceAtLeast(0L)
    }

    
    /**
     * Helper function to match iOS stride functionality
     * stride(from: 0, to: fullData.count, by: maxFragmentSize)
     */
    private fun <T> stride(from: Int, to: Int, by: Int, transform: (Int) -> T): List<T> {
        val result = mutableListOf<T>()
        var current = from
        while (current < to) {
            result.add(transform(current))
            current += by
        }
        return result
    }
    
    /**
     * iOS cleanup - exactly matching performCleanup() implementation
     * Clean old fragments (> 30 seconds old)
     */
    private fun cleanupOldFragments() {
        synchronized(fragmentStateLock) {
            val now = System.currentTimeMillis()
            val cutoff = now - FRAGMENT_TIMEOUT

            val oldFragments = fragmentSets.filter { it.value.createdAt < cutoff }.map { it.key }

            for (fragmentID in oldFragments) {
                removeFragmentSetLocked(fragmentID)
            }
        }
    }
    
    /**
     * Get debug information - matches iOS debugging
     */
    fun getDebugInfo(): String {
        synchronized(fragmentStateLock) {
            return buildString {
                appendLine("=== Fragment Manager Debug Info (iOS Compatible) ===")
                appendLine("Active Fragment Sets: ${fragmentSets.size}")
                appendLine("Fragment Size Threshold: $FRAGMENT_SIZE_THRESHOLD bytes")
                appendLine("Max Fragment Size: $MAX_FRAGMENT_SIZE bytes")
                appendLine("Global Buffered Bytes: $globalBufferedBytes")

                fragmentSets.forEach { (setKey, set) ->
                    val ageSeconds = (System.currentTimeMillis() - set.createdAt) / 1000
                    appendLine("  - $setKey: ${set.receivedCount}/${set.total} fragments, bytes=${set.bytes}, type: ${set.originalType}, age: ${ageSeconds}s")
                }
            }
        }
    }
    
    /**
     * Start periodic cleanup of old fragments - matches iOS maintenance timer
     */
    private fun startPeriodicCleanup() {
        managerScope.launch {
            while (isActive) {
                delay(CLEANUP_INTERVAL)
                cleanupOldFragments()
            }
        }
    }
    
    /**
     * Clear all fragments
     */
    fun clearAllFragments() {
        synchronized(fragmentStateLock) {
            fragmentSets.clear()
            linkActiveSets.clear()
            linkBufferedBytes.clear()
            globalBufferedBytes = 0L
        }
    }
    
    /**
     * Shutdown the manager
     */
    fun shutdown() {
        managerScope.cancel()
        clearAllFragments()
    }
}

/**
 * Delegate interface for fragment manager callbacks
 */
interface FragmentManagerDelegate {
    fun onPacketReassembled(packet: BitchatPacket)
}
