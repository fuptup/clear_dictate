package com.cleardictate.android.accessibility

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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
        assertTrue(shouldUseNativePaste(-1, -1, pasteSupported = true, setTextSupported = true, isEditable = true))
    }

    @Test
    fun `cursor-aware editors retain complete-value replacement`()
    {
        assertFalse(shouldUseNativePaste(7, 7, pasteSupported = true, setTextSupported = true, isEditable = true))
    }

    @Test
    fun `editors without paste support cannot use native paste`()
    {
        assertFalse(shouldUseNativePaste(-1, -1, pasteSupported = false, setTextSupported = true, isEditable = true))
    }

    @Test
    fun `paste-only custom editors use native paste even when they expose a cursor`()
    {
        assertTrue(shouldUseNativePaste(7, 7, pasteSupported = true, setTextSupported = false, isEditable = false))
    }

    @Test
    fun `editable custom editors can attempt native paste when they omit insertion actions`()
    {
        assertTrue(shouldUseNativePaste(7, 7, pasteSupported = false, setTextSupported = false, isEditable = true))
    }

    @Test
    fun `custom editors are supported by either insertion action without an editable flag`()
    {
        assertTrue(isSupportedAccessibilityEditor(isEditable = false, isEnabled = true, isVisibleToUser = true, setTextSupported = true, pasteSupported = false))
        assertTrue(isSupportedAccessibilityEditor(isEditable = false, isEnabled = true, isVisibleToUser = true, setTextSupported = false, pasteSupported = true))
        assertTrue(isSupportedAccessibilityEditor(isEditable = true, isEnabled = true, isVisibleToUser = true, setTextSupported = false, pasteSupported = false))
    }

    @Test
    fun `nodes without usable insertion actions remain hidden`()
    {
        assertFalse(isSupportedAccessibilityEditor(isEditable = false, isEnabled = true, isVisibleToUser = true, setTextSupported = false, pasteSupported = false))
        assertFalse(isSupportedAccessibilityEditor(isEditable = true, isEnabled = false, isVisibleToUser = true, setTextSupported = true, pasteSupported = true))
        assertFalse(isSupportedAccessibilityEditor(isEditable = true, isEnabled = true, isVisibleToUser = false, setTextSupported = true, pasteSupported = true))
    }

    @Test
    fun `floating control appears only for non-sensitive editors outside active recording`()
    {
        assertTrue(shouldShowFloatingDictationControl(recordingActive = false, editorSupported = true, dictationAllowed = true))
        assertFalse(shouldShowFloatingDictationControl(recordingActive = false, editorSupported = true, dictationAllowed = false))
        assertFalse(shouldShowFloatingDictationControl(recordingActive = false, editorSupported = false, dictationAllowed = true))
        assertTrue(shouldShowFloatingDictationControl(recordingActive = true, editorSupported = false, dictationAllowed = false))
    }

    @Test
    fun `undo uses complete replacement when the editor supports set text`()
    {
        assertEquals(
            AccessibilityUndoExecutionMethod.COMPLETE_TEXT_REPLACEMENT,
            selectAccessibilityUndoExecution(replacementAvailable = true, setTextSupported = true, nativeUndoAvailable = false, nativeUndoSafe = false)
        )
    }

    @Test
    fun `undo uses the editor command only when the whole document is unchanged`()
    {
        assertEquals(
            AccessibilityUndoExecutionMethod.NATIVE_EDITOR_UNDO,
            selectAccessibilityUndoExecution(replacementAvailable = true, setTextSupported = false, nativeUndoAvailable = true, nativeUndoSafe = true)
        )
        assertNull(selectAccessibilityUndoExecution(replacementAvailable = true, setTextSupported = false, nativeUndoAvailable = true, nativeUndoSafe = false))
    }

    @Test
    fun `native undo control must be actionable and owned by the focused application`()
    {
        assertTrue(isNativeEditorUndoControl("Undo", "com.example.docs", "com.example.docs", clickable = true, enabled = true, visible = true, clickSupported = true))
        assertFalse(isNativeEditorUndoControl("Undo", "com.other", "com.example.docs", clickable = true, enabled = true, visible = true, clickSupported = true))
        assertFalse(isNativeEditorUndoControl("Undo", "com.example.docs", "com.example.docs", clickable = true, enabled = false, visible = true, clickSupported = true))
    }
}
