package com.bitchat.android.features.file

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class IncomingFileQuotaTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private var now = 1_000_000L

    private fun quota(
        maxTotalBytes: Long = 1_000,
        maxFileCount: Int = 100,
        maxSenderBytes: Long = 1_000,
        maxSenderFiles: Int = 100
    ) = IncomingFileQuota(
        maxTotalBytes = maxTotalBytes,
        maxFileCount = maxFileCount,
        senderWindowMs = 60_000,
        maxSenderBytes = maxSenderBytes,
        maxSenderFiles = maxSenderFiles,
        clock = { now }
    )

    private fun store(dir: File, name: String, size: Int, modified: Long): File =
        File(dir, name).apply {
            writeBytes(ByteArray(size))
            setLastModified(modified)
        }

    @Test
    fun rejectsFileLargerThanGlobalCap() {
        val dir = tmp.newFolder("incoming")
        assertFalse(quota(maxTotalBytes = 100).admit("peer", 101, listOf(dir)))
    }

    @Test
    fun limitsFilesPerSenderWithinWindow() {
        val dir = tmp.newFolder("incoming")
        val q = quota(maxSenderFiles = 2)
        assertTrue(q.admit("peer", 10, listOf(dir)))
        assertTrue(q.admit("peer", 10, listOf(dir)))
        assertFalse(q.admit("peer", 10, listOf(dir)))
        assertTrue(q.admit("other", 10, listOf(dir)))

        now += 60_000
        assertTrue(q.admit("peer", 10, listOf(dir)))
    }

    @Test
    fun limitsBytesPerSenderWithinWindow() {
        val dir = tmp.newFolder("incoming")
        val q = quota(maxSenderBytes = 100)
        assertTrue(q.admit("peer", 60, listOf(dir)))
        assertFalse(q.admit("peer", 60, listOf(dir)))
        assertTrue(q.admit("peer", 40, listOf(dir)))
    }

    @Test
    fun evictsOldestFilesToStayUnderByteCap() {
        val images = tmp.newFolder("images")
        val files = tmp.newFolder("files")
        val oldest = store(images, "a.jpg", 400, 1_000)
        val middle = store(files, "b.bin", 400, 2_000)
        val newest = store(images, "c.jpg", 100, 3_000)

        assertTrue(quota(maxTotalBytes = 1_000).admit("peer", 300, listOf(images, files)))

        assertFalse(oldest.exists())
        assertTrue(middle.exists())
        assertTrue(newest.exists())
    }

    @Test
    fun evictsOldestFilesToStayUnderCountCap() {
        val dir = tmp.newFolder("incoming")
        (0 until 3).forEach { store(dir, "f$it", 1, 1_000L + it) }

        assertTrue(quota(maxFileCount = 3).admit("peer", 1, listOf(dir)))

        assertEquals(setOf("f1", "f2"), dir.listFiles()!!.map { it.name }.toSet())
    }
}
