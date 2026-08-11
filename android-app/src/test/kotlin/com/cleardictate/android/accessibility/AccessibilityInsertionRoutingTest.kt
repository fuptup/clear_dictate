package com.cleardictate.android.accessibility

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Specifies how editor metadata selects placeholder normalization and native paste behavior.
 */
class AccessibilityInsertionRoutingTest
{
    @Test
    fun `WhatsApp composer is empty while its voice-note control is visible`()
    {
        assertTrue(isWhatsAppEmptyComposer("com.whatsapp", "com.whatsapp:id/entry", true))
    }

    @Test
    fun `WhatsApp composer is not empty after its voice-note control disappears`()
    {
        assertFalse(isWhatsAppEmptyComposer("com.whatsapp", "com.whatsapp:id/entry", false))
    }

    @Test
    fun `another application cannot be mistaken for an empty WhatsApp composer`()
    {
        assertFalse(isWhatsAppEmptyComposer("com.example.editor", "com.whatsapp:id/entry", true))
    }

    @Test
    fun `cursor-hidden editors continue to use native paste`()
    {
        assertTrue(shouldUseNativePaste(-1, -1, true))
    }

    @Test
    fun `cursor-aware editors retain complete-value replacement`()
    {
        assertFalse(shouldUseNativePaste(7, 7, true))
    }

    @Test
    fun `editors without paste support cannot use native paste`()
    {
        assertFalse(shouldUseNativePaste(-1, -1, false))
    }
}
