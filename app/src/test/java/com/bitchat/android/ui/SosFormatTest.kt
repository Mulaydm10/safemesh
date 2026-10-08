package com.bitchat.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SosFormatTest {
    @Test
    fun buildIncludesStatusAndLocation() {
        val text = SosFormat.build(SosStatus.INJURED, 12.345678, 98.765432, 15f)
        assertEquals("SOS! INJURED - need help. Location: 12.34568,98.76543 ±15m geo:12.34568,98.76543", text)
        assertTrue(SosFormat.isSos(text))
    }

    @Test
    fun buildWithoutLocation() {
        val text = SosFormat.build(SosStatus.MEDIC, null, null, null)
        assertEquals("SOS! NEED MEDIC - need help. Location: unknown", text)
    }

    @Test
    fun normalMessagesAreNotSos() {
        assertFalse(SosFormat.isSos("hello sos!"))
        assertFalse(SosFormat.isSos("where are you"))
    }
}
