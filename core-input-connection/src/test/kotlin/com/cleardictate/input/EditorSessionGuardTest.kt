package com.cleardictate.input

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Verifies that completed work cannot cross an immutable editor-session boundary.
 */
class EditorSessionGuardTest
{
    @Test
    fun `accepts only the editor session that started recording`()
    {
        val recordingSession = EditorSessionIdentifier("session-a")

        assertTrue(EditorSessionGuard.matches(recordingSession, EditorSessionIdentifier("session-a")))
        assertFalse(EditorSessionGuard.matches(recordingSession, EditorSessionIdentifier("session-b")))
    }

    @Test
    fun `empty identifiers fail closed`()
    {
        assertFalse(EditorSessionGuard.matches(EditorSessionIdentifier(""), EditorSessionIdentifier("")))
    }
}
