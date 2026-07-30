package com.cleardictate.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Specifies platform-neutral insertion, privacy, and stale-session protection.
 */
class TranscriptInsertionPolicyTest
{
    private val insertionPolicy = TranscriptInsertionPolicy()

    @Test
    fun `adds a leading space after an existing word`()
    {
        val decision = insertionPolicy.decide(EditorContext(textBeforeCursor = "Hello", textAfterCursor = "", hasSelection = false), "world")

        assertEquals(" world", decision.textToInsert)
    }

    @Test
    fun `does not add a space before punctuation`()
    {
        val decision = insertionPolicy.decide(EditorContext(textBeforeCursor = "Hello", textAfterCursor = "", hasSelection = false), ".")

        assertEquals(".", decision.textToInsert)
    }

    @Test
    fun `does not duplicate spaces around cursor`()
    {
        val decision = insertionPolicy.decide(EditorContext(textBeforeCursor = "Hello ", textAfterCursor = " world", hasSelection = false), "clear")

        assertEquals("clear", decision.textToInsert)
    }

    @Test
    fun `blocks sensitive and private-field history`()
    {
        val sensitiveDecision = insertionPolicy.decide(EditorContext(isSensitive = true), "secret")
        val privateDecision = insertionPolicy.decide(EditorContext(isPrivate = true), "private note")

        assertFalse(sensitiveDecision.insertionAllowed)
        assertFalse(sensitiveDecision.historyAllowed)
        assertTrue(privateDecision.insertionAllowed)
        assertFalse(privateDecision.historyAllowed)
    }

    @Test
    fun `blocks insertion when editor session changed`()
    {
        val decision = insertionPolicy.decide(
            editorContext = EditorContext(editorSessionIdentifier = "new-session"),
            transcript = "stale transcript",
            recordingEditorSessionIdentifier = "old-session"
        )

        assertFalse(decision.insertionAllowed)
        assertEquals(InsertionBlockReason.STALE_EDITOR_SESSION, decision.blockReason)
    }

    @Test
    fun `does not replace a selection unless explicitly enabled`()
    {
        val blockedDecision = insertionPolicy.decide(EditorContext(hasSelection = true, replaceSelectionEnabled = false), "replacement")
        val allowedDecision = insertionPolicy.decide(EditorContext(hasSelection = true, replaceSelectionEnabled = true), "replacement")

        assertFalse(blockedDecision.insertionAllowed)
        assertTrue(allowedDecision.insertionAllowed)
    }
}
