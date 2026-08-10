package com.cleardictate.android.accessibility

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Verifies focused-field fencing, selection replacement, and shared conservative spacing.
 */
class AccessibilityTextInsertionPlannerTest
{
    private val planner = AccessibilityTextInsertionPlanner()
    private val identity = AccessibilityFieldIdentity(3, "com.example", "EditText", "message", 10, 20, 500, 120)

    @Test
    fun `inserts at the cursor with conservative spacing`()
    {
        val replacement = planner.plan(identity, AccessibilityEditableText(identity, "Hello world", 5, 5, false), "clear dictate")

        assertEquals(AccessibilityTextReplacement("Hello clear dictate world", 19, 5, 19), replacement)
    }

    @Test
    fun `replaces the selected range`()
    {
        val replacement = planner.plan(identity, AccessibilityEditableText(identity, "Hello old world", 6, 9, false), "new")

        assertEquals(AccessibilityTextReplacement("Hello new world", 9, 6, 9), replacement)
    }

    @Test
    fun `accepts the same identified editor after its bounds change`()
    {
        val resizedIdentity = identity.copy(top = 10, bottom = 180)

        val replacement = planner.plan(identity, AccessibilityEditableText(resizedIdentity, "", 0, 0, false), "clear dictate")

        assertEquals(AccessibilityTextReplacement("clear dictate", 13, 0, 13), replacement)
    }

    @Test
    fun `uses overlapping bounds to distinguish editors without view identifiers`()
    {
        val unidentifiedField = identity.copy(viewIdentifier = "")
        val overlappingField = unidentifiedField.copy(top = 15, bottom = 180)
        val separateField = unidentifiedField.copy(top = 200, bottom = 280)

        assertEquals(
            AccessibilityTextReplacement("clear dictate", 13, 0, 13),
            planner.plan(unidentifiedField, AccessibilityEditableText(overlappingField, "", 0, 0, false), "clear dictate")
        )
        assertNull(planner.plan(unidentifiedField, AccessibilityEditableText(separateField, "", 0, 0, false), "clear dictate"))
    }

    @Test
    fun `rejects changed and sensitive editors`()
    {
        val changedIdentity = identity.copy(viewIdentifier = "subject")

        assertNull(planner.plan(identity, AccessibilityEditableText(changedIdentity, "", 0, 0, false), "text"))
        assertNull(planner.plan(identity, AccessibilityEditableText(identity, "secret", 6, 6, true), "text"))
    }

    @Test
    fun `appends without replacing text when the editor omits selection offsets`()
    {
        val replacement = planner.plan(identity, AccessibilityEditableText(identity, "Existing", -1, -1, false), "dictated text")

        assertEquals(AccessibilityTextReplacement("Existing dictated text", 22, 8, 22), replacement)
    }

    @Test
    fun `normalizes spacing around the range revealed by native paste`()
    {
        val currentField = AccessibilityEditableText(identity, "Existingdictated text", -1, -1, false)

        val replacement = planner.normalizePastedRange(currentField, 8, 21)

        assertEquals(AccessibilityTextReplacement("Existing dictated text", 22, 8, 22), replacement)
    }

    @Test
    fun `does not add boundary spacing in an empty editor`()
    {
        val currentField = AccessibilityEditableText(identity, "dictated text", -1, -1, false)

        val replacement = planner.normalizePastedRange(currentField, 0, 13)

        assertEquals(AccessibilityTextReplacement("dictated text", 13, 0, 13), replacement)
    }
}
