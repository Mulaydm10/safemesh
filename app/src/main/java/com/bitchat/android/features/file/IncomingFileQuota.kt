package com.bitchat.android.features.file

import java.io.File

/**
 * Bounds what remote peers can make us write to disk. Every received file is
 * auto-saved, and announce identities are self-issued, so without limits any
 * nearby peer could fill storage. Enforces a per-sender sliding-window budget
 * and a global byte/count cap over the incoming media dirs, evicting the
 * oldest stored files to make room.
 */
class IncomingFileQuota(
    private val maxTotalBytes: Long = MAX_TOTAL_BYTES,
    private val maxFileCount: Int = MAX_FILE_COUNT,
    private val senderWindowMs: Long = SENDER_WINDOW_MS,
    private val maxSenderBytes: Long = MAX_SENDER_BYTES_PER_WINDOW,
    private val maxSenderFiles: Int = MAX_SENDER_FILES_PER_WINDOW,
    private val maxTrackedSenders: Int = MAX_TRACKED_SENDERS,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private class Usage(val atMs: Long, val bytes: Long)

    private val senders = object : LinkedHashMap<String, ArrayDeque<Usage>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ArrayDeque<Usage>>?): Boolean =
            size > maxTrackedSenders
    }

    /**
     * Returns true if a file of [sizeBytes] from [senderKey] may be stored in
     * [dirs]. On success the sender's budget is charged and the oldest files in
     * [dirs] are deleted until the new file fits under the global caps.
     */
    @Synchronized
    fun admit(senderKey: String, sizeBytes: Long, dirs: List<File>): Boolean {
        if (sizeBytes < 0 || sizeBytes > maxTotalBytes || sizeBytes > maxSenderBytes) return false

        val now = clock()
        val history = senders.getOrPut(senderKey) { ArrayDeque() }
        while (history.isNotEmpty() && now - history.first().atMs >= senderWindowMs) {
            history.removeFirst()
        }
        val senderBytes = history.sumOf { it.bytes }
        if (history.size + 1 > maxSenderFiles || senderBytes + sizeBytes > maxSenderBytes) {
            return false
        }

        if (!evictToFit(sizeBytes, dirs)) return false
        history.addLast(Usage(now, sizeBytes))
        return true
    }

    private fun evictToFit(sizeBytes: Long, dirs: List<File>): Boolean {
        val stored = dirs
            .flatMap { dir -> dir.listFiles()?.filter { it.isFile }.orEmpty() }
            .sortedBy { it.lastModified() }
            .toMutableList()
        var totalBytes = stored.sumOf { it.length() }
        var count = stored.size
        val iterator = stored.iterator()
        while ((totalBytes + sizeBytes > maxTotalBytes || count + 1 > maxFileCount) && iterator.hasNext()) {
            val oldest = iterator.next()
            val length = oldest.length()
            if (oldest.delete()) {
                totalBytes -= length
                count--
            }
        }
        return totalBytes + sizeBytes <= maxTotalBytes && count + 1 <= maxFileCount
    }

    companion object {
        const val MAX_TOTAL_BYTES = 100L * 1024 * 1024
        const val MAX_FILE_COUNT = 500
        const val SENDER_WINDOW_MS = 10 * 60 * 1000L
        const val MAX_SENDER_BYTES_PER_WINDOW = 16L * 1024 * 1024
        const val MAX_SENDER_FILES_PER_WINDOW = 60
        const val MAX_TRACKED_SENDERS = 1024

        val shared = IncomingFileQuota()
    }
}
