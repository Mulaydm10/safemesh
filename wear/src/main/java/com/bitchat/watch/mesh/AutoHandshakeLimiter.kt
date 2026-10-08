package com.bitchat.watch.mesh

/**
 * Bounds the watch's announce-driven Noise handshakes. Peer IDs are cheap to mint, so each
 * peer gets a cooldown, the whole service gets a rolling-window budget, and the number of
 * tracked peers is capped so state cannot grow without limit.
 */
internal class AutoHandshakeLimiter(
    private val perPeerCooldownMs: Long = 60_000L,
    private val globalWindowMs: Long = 60_000L,
    private val maxPerWindow: Int = 8,
    private val maxTrackedPeers: Int = 64
) {
    private val lastAttemptByPeer = LinkedHashMap<String, Long>()
    private val recentAttempts = ArrayDeque<Long>()

    @Synchronized
    fun tryAcquire(peerID: String, nowMs: Long): Boolean {
        prune(nowMs)
        val last = lastAttemptByPeer[peerID]
        if (last != null && nowMs - last < perPeerCooldownMs) return false
        if (recentAttempts.size >= maxPerWindow) return false
        lastAttemptByPeer.remove(peerID)
        while (lastAttemptByPeer.size >= maxTrackedPeers) {
            lastAttemptByPeer.remove(lastAttemptByPeer.keys.first())
        }
        lastAttemptByPeer[peerID] = nowMs
        recentAttempts.addLast(nowMs)
        return true
    }

    @Synchronized
    fun forget(peerID: String) {
        lastAttemptByPeer.remove(peerID)
    }

    @Synchronized
    fun clear() {
        lastAttemptByPeer.clear()
        recentAttempts.clear()
    }

    @Synchronized
    fun trackedPeerCount(): Int = lastAttemptByPeer.size

    private fun prune(nowMs: Long) {
        while (recentAttempts.isNotEmpty() && nowMs - recentAttempts.first() >= globalWindowMs) {
            recentAttempts.removeFirst()
        }
        val iterator = lastAttemptByPeer.entries.iterator()
        while (iterator.hasNext()) {
            if (nowMs - iterator.next().value >= perPeerCooldownMs) iterator.remove()
        }
    }
}
