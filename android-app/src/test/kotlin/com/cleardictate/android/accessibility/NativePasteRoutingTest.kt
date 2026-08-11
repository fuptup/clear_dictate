package com.cleardictate.android.accessibility

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Specifies when an editor must use Android's native paste path instead of complete-value replacement.
 */
class NativePasteRoutingTest
{
    @Test
    fun `WhatsApp uses native paste even when its composer reports a cursor`()
    {
        assertTrue(shouldUseNativePaste("com.whatsapp", 7, 7, true))
    }

    @Test
    fun `cursor-hidden editors continue to use native paste`()
    {
        assertTrue(shouldUseNativePaste("com.example.editor", -1, -1, true))
    }

    @Test
    fun `ordinary cursor-aware editors retain complete-value replacement`()
    {
        assertFalse(shouldUseNativePaste("com.example.editor", 7, 7, true))
    }

    @Test
    fun `editors without paste support cannot use native paste`()
    {
        assertFalse(shouldUseNativePaste("com.whatsapp", 7, 7, false))
    }
}
