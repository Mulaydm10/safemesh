package com.bitchat.android.mesh

import com.bitchat.android.model.RoutedPacket
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import com.bitchat.android.protocol.SpecialRecipients
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PacketProcessorActorBoundsTest {
    private val processors = mutableListOf<PacketProcessor>()

    @After
    fun tearDown() {
        processors.forEach(PacketProcessor::shutdown)
    }

    @Test
    fun `flood of distinct unauthenticated sender IDs keeps actor count bounded`() {
        val processor = processor(RejectingDelegate())

        repeat(PacketProcessor.MAX_PEER_ACTORS * 20) { i ->
            val peerID = "%016x".format(i.toLong())
            processor.processPacket(RoutedPacket(packet(peerID), peerID, "attacker-link"))
        }

        assertTrue(processor.activePeerActorCount() <= PacketProcessor.MAX_PEER_ACTORS)
    }

    @Test
    fun `packets beyond a single peer queue capacity are dropped`() = runBlocking {
        val delegate = BlockingDelegate()
        val processor = processor(delegate)
        val total = PacketProcessor.PEER_ACTOR_QUEUE_CAPACITY * 3

        repeat(total) {
            processor.processPacket(RoutedPacket(packet(PEER_ID), PEER_ID, "attacker-link"))
        }
        assertTrue(delegate.firstStarted.await(1, TimeUnit.SECONDS))
        // Queue contents are fixed while the first packet is blocked; a sentinel would itself be dropped.
        delegate.release.countDown()

        val expectedMax = PacketProcessor.PEER_ACTOR_QUEUE_CAPACITY + 1
        withTimeout(5_000) {
            while (delegate.seen.get() < PacketProcessor.PEER_ACTOR_QUEUE_CAPACITY) {
                yield()
            }
        }
        assertTrue(delegate.seen.get() <= expectedMax)
        assertTrue(delegate.seen.get() < total)
    }

    @Test
    fun `evicted peer gets a fresh actor and is still processed`() = runBlocking {
        val delegate = RejectingDelegate()
        val processor = processor(delegate)

        repeat(PacketProcessor.MAX_PEER_ACTORS + 1) { i ->
            val peerID = "%016x".format(i.toLong())
            processor.processPacket(RoutedPacket(packet(peerID), peerID, "link"))
        }
        val evicted = "%016x".format(0L)
        withTimeout(5_000) { while (delegate.count(evicted) < 1) yield() }
        processor.processPacket(RoutedPacket(packet(evicted), evicted, "link"))

        withTimeout(5_000) { while (delegate.count(evicted) < 2) yield() }
        assertEquals(2, delegate.count(evicted))
    }

    private fun processor(delegate: PacketProcessorDelegate): PacketProcessor =
        PacketProcessor(MY_PEER_ID).also {
            it.delegate = delegate
            processors += it
        }

    private fun packet(peerID: String) = BitchatPacket(
        version = 1u,
        type = MessageType.ANNOUNCE.value,
        senderID = peerID.hexToBytes(),
        recipientID = SpecialRecipients.BROADCAST,
        timestamp = System.currentTimeMillis().toULong(),
        payload = byteArrayOf(0x01),
        ttl = 7u
    )

    private open class RejectingDelegate : PacketProcessorDelegate {
        private val counts = ConcurrentHashMap<String, AtomicInteger>()

        fun count(peerID: String): Int = counts[peerID]?.get() ?: 0

        override fun validatePacketSecurity(packet: BitchatPacket, peerID: String): Boolean {
            counts.getOrPut(peerID) { AtomicInteger() }.incrementAndGet()
            return false
        }
        override fun updatePeerLastSeen(peerID: String) = Unit
        override fun getPeerNickname(peerID: String): String? = null
        override fun getNetworkSize() = 1
        override fun getBroadcastRecipient(): ByteArray = SpecialRecipients.BROADCAST
        override fun handleNoiseHandshake(routed: RoutedPacket) = false
        override fun handleNoiseEncrypted(routed: RoutedPacket) = false
        override suspend fun handleAnnounce(routed: RoutedPacket) = false
        override fun handleMessage(routed: RoutedPacket) = Unit
        override fun handleLeave(routed: RoutedPacket) = Unit
        override fun handleFragment(packet: BitchatPacket): BitchatPacket? = null
        override fun handleRequestSync(routed: RoutedPacket) = Unit
        override fun sendAnnouncementToPeer(peerID: String) = Unit
        override fun sendCachedMessages(peerID: String) = Unit
        override fun relayPacket(routed: RoutedPacket) = Unit
        override fun sendToPeer(peerID: String, routed: RoutedPacket) = false
    }

    private class BlockingDelegate : RejectingDelegate() {
        val firstStarted = CountDownLatch(1)
        val release = CountDownLatch(1)
        val seen = AtomicInteger()

        override fun validatePacketSecurity(packet: BitchatPacket, peerID: String): Boolean {
            seen.incrementAndGet()
            firstStarted.countDown()
            release.await(5, TimeUnit.SECONDS)
            return false
        }
    }

    private fun String.hexToBytes(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private companion object {
        const val MY_PEER_ID = "1111222233334444"
        const val PEER_ID = "aaaabbbbccccdddd"
    }
}
