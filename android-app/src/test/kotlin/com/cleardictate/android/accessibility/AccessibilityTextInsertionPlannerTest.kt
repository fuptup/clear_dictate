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

        assertEquals(AccessibilityTextReplacement("Hello clear dictate world", 19), replacement)
    }

    @Test
    fun `replaces the selected range`()
    {
        val replacement = planner.plan(identity, AccessibilityEditableText(identity, "Hello old world", 6, 9, false), "new")

        assertEquals(AccessibilityTextReplacement("Hello new world", 9), replacement)
    }

    @Test
    fun `rejects changed sensitive and invalid editors`()
    {
        val changedIdentity = identity.copy(viewIdentifier = "subject")

        assertNull(planner.plan(identity, AccessibilityEditableText(changedIdentity, "", 0, 0, false), "text"))
        assertNull(planner.plan(identity, AccessibilityEditableText(identity, "secret", 6, 6, true), "text"))
        assertNull(planner.plan(identity, AccessibilityEditableText(identity, "short", 9, 9, false), "text"))
    }
}
