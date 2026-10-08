package com.bitchat.android.mesh

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupEnvelopeTest {
    @After
    fun tearDown() = GroupKeyring.clear()

    @Test
    fun sealOpenRoundTrip() {
        val key = GroupEnvelope.deriveKey("medics", "pw-123")
        val sealed = GroupEnvelope.seal(key, "meet at gate 3")
        assertTrue(GroupEnvelope.isEnvelope(sealed))
        assertFalse(sealed.contains("gate"))
        assertFalse(sealed.contains("medics"))
        assertEquals("meet at gate 3", GroupEnvelope.open(key, sealed))
    }

    @Test
    fun sameNameAndPasswordGiveSameGroup() {
        val a = GroupEnvelope.deriveKey("#Medics", "pw-123")
        val b = GroupEnvelope.deriveKey("medics", "pw-123")
        assertEquals(GroupEnvelope.groupId(a), GroupEnvelope.groupId(b))
    }

    @Test
    fun wrongPasswordCannotRead() {
        val right = GroupEnvelope.deriveKey("medics", "pw-123")
        val wrong = GroupEnvelope.deriveKey("medics", "pw-124")
        assertNotEquals(GroupEnvelope.groupId(right), GroupEnvelope.groupId(wrong))
        assertNull(GroupEnvelope.open(wrong, GroupEnvelope.seal(right, "secret")))
    }

    @Test
    fun tamperedCiphertextIsRejected() {
        val key = GroupEnvelope.deriveKey("medics", "pw-123")
        val sealed = GroupEnvelope.seal(key, "secret")
        val last = sealed.last()
        val tampered = sealed.dropLast(1) + (if (last == 'A') 'B' else 'A')
        assertNull(GroupEnvelope.open(key, tampered))
    }

    @Test
    fun keyringRoutesToJoinedChannelOnly() {
        GroupKeyring.add("#medics", GroupEnvelope.deriveKey("medics", "pw-123"))
        val sealed = GroupKeyring.seal("#medics", "hello")!!
        assertEquals("#medics" to "hello", GroupKeyring.open(sealed))
        assertNull(GroupKeyring.seal("#other", "hello"))
        GroupKeyring.clear()
        assertNull(GroupKeyring.open(sealed))
    }
}
