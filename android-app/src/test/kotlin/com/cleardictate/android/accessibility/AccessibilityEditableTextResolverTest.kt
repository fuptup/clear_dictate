package com.cleardictate.android.accessibility

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Specifies placeholder normalization for standard and partially implemented accessibility editors.
 */
class AccessibilityEditableTextResolverTest
{
    @Test
    fun `removes an unmarked hint when the editor also omits its cursor`()
    {
        assertEquals("", resolveAccessibilityEditableText("Message", "Message", false, -1, -1))
    }

    @Test
    fun `preserves actual text that happens to equal the hint when a cursor is available`()
    {
        assertEquals("Message", resolveAccessibilityEditableText("Message", "Message", false, 7, 7))
    }

    @Test
    fun `removes a hint explicitly marked by Android`()
    {
        assertEquals("", resolveAccessibilityEditableText("Write something", "Write something", true, 0, 0))
    }

    @Test
    fun `preserves ordinary draft text`()
    {
        assertEquals("Existing draft", resolveAccessibilityEditableText("Existing draft", "Message", false, -1, -1))
    }
}
