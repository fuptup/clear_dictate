package com.cleardictate.android.accessibility

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Verifies that undo removes only one unchanged inserted range and retains no reversible transcript copy.
 */
class AccessibilityInsertionUndoPlannerTest
{
    private val planner = AccessibilityInsertionUndoPlanner()
    private val identity = AccessibilityFieldIdentity(3, "com.example", "EditText", "message", 10, 20, 500, 120)

    @Test
    fun `removes only the captured insertion while preserving later text`()
    {
        val replacement = AccessibilityTextReplacement("Prior dictated later", 14, 5, 14)
        val record = assertNotNull(planner.capture(identity, replacement))
        val currentField = AccessibilityEditableText(identity, "Prior dictated later!", 21, 21, false)

        val undo = planner.plan(record, currentField)

        assertEquals(AccessibilityUndoReplacement("Prior later!", 5), undo)
    }

    @Test
    fun `rejects undo after the inserted segment or focused field changes`()
    {
        val replacement = AccessibilityTextReplacement("Prior dictated later", 14, 5, 14)
        val record = assertNotNull(planner.capture(identity, replacement))
        val changedText = AccessibilityEditableText(identity, "Prior modified later", 14, 14, false)
        val changedField = AccessibilityEditableText(identity.copy(viewIdentifier = "subject"), replacement.text, 14, 14, false)

        assertNull(planner.plan(record, changedText))
        assertNull(planner.plan(record, changedField))
    }

    @Test
    fun `keeps undo while a document retains the dictated range`()
    {
        val replacement = AccessibilityTextReplacement("Prior dictated", 14, 6, 14)
        val record = assertNotNull(planner.capture(identity, replacement))
        val continuedDocument = AccessibilityEditableText(identity, "Prior dictated and then more", 28, 28, false)

        assertTrue(planner.isUndoAvailable(record, continuedDocument))
    }

    @Test
    fun `retires undo when a messaging composer consumes the dictated range`()
    {
        val replacement = AccessibilityTextReplacement("dictated", 8, 0, 8)
        val record = assertNotNull(planner.capture(identity, replacement))
        val clearedComposer = AccessibilityEditableText(identity, "", 0, 0, false)

        assertFalse(planner.isUndoAvailable(record, clearedComposer))
    }

    @Test
    fun `captures a verified native paste without retaining its text`()
    {
        val pendingPaste = assertNotNull(planner.expectPaste(identity, "dictated"))
        val currentField = AccessibilityEditableText(identity, "Prior dictated", -1, -1, false)

        val record = planner.capturePaste(pendingPaste, currentField, 6, 8, 0)

        assertNotNull(record)
        assertEquals(6, record.insertedTextStart)
        assertEquals(8, record.insertedTextLength)
        assertEquals(AccessibilityUndoReplacement("Prior ", 6), planner.plan(record, currentField))
    }

    @Test
    fun `captures a uniquely identifiable native paste when the editor emits no text change event`()
    {
        val pendingPaste = assertNotNull(planner.expectPaste(identity, "dictated"))
        val currentField = AccessibilityEditableText(identity, "Prior dictated text", -1, -1, false)

        val record = planner.captureUnreportedPaste(pendingPaste, currentField)

        assertNotNull(record)
        assertEquals(6, record.insertedTextStart)
        assertEquals(8, record.insertedTextLength)
        assertEquals(AccessibilityUndoReplacement("Prior  text", 6), planner.plan(record, currentField))
    }

    @Test
    fun `refuses an unreported native paste when the inserted range is ambiguous`()
    {
        val pendingPaste = assertNotNull(planner.expectPaste(identity, "same"))
        val currentField = AccessibilityEditableText(identity, "same and same", -1, -1, false)

        assertNull(planner.captureUnreportedPaste(pendingPaste, currentField))
    }

    @Test
    fun `rejects a paste event that replaced text or does not match the expected transcript`()
    {
        val pendingPaste = assertNotNull(planner.expectPaste(identity, "dictated"))

        assertNull(planner.capturePaste(pendingPaste, AccessibilityEditableText(identity, "Prior dictated", -1, -1, false), 6, 8, 2))
        assertNull(planner.capturePaste(pendingPaste, AccessibilityEditableText(identity, "Prior modified", -1, -1, false), 6, 8, 0))
    }
}
