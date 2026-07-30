package com.cleardictate.input

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
    private val editorSessionIdentifier = EditorSessionIdentifier("editor-session")

    @Test
    fun `adds a leading space after an existing word`()
    {
        val decision = insertionPolicy.decide(
            EditorContext(editorSessionIdentifier, textBeforeCursor = "Hello", textAfterCursor = "", hasSelection = false),
            "world",
            editorSessionIdentifier
        )

        assertEquals(" world", decision.textToInsert)
    }

    @Test
    fun `does not add a space before punctuation`()
    {
        val decision = insertionPolicy.decide(
            EditorContext(editorSessionIdentifier, textBeforeCursor = "Hello", textAfterCursor = "", hasSelection = false),
            ".",
            editorSessionIdentifier
        )

        assertEquals(".", decision.textToInsert)
    }

    @Test
    fun `does not duplicate spaces around cursor`()
    {
        val decision = insertionPolicy.decide(
            EditorContext(editorSessionIdentifier, textBeforeCursor = "Hello ", textAfterCursor = " world", hasSelection = false),
            "clear",
            editorSessionIdentifier
        )

        assertEquals("clear", decision.textToInsert)
    }

    @Test
    fun `blocks sensitive and private-field history`()
    {
        val sensitiveDecision = insertionPolicy.decide(
            EditorContext(editorSessionIdentifier, isSensitive = true),
            "secret",
            editorSessionIdentifier
        )
        val privateDecision = insertionPolicy.decide(
            EditorContext(editorSessionIdentifier, isPrivate = true),
            "private note",
            editorSessionIdentifier
        )

        assertFalse(sensitiveDecision.insertionAllowed)
        assertFalse(sensitiveDecision.historyAllowed)
        assertTrue(privateDecision.insertionAllowed)
        assertFalse(privateDecision.historyAllowed)
    }

    @Test
    fun `blocks insertion when editor session changed`()
    {
        val decision = insertionPolicy.decide(
            editorContext = EditorContext(editorSessionIdentifier = EditorSessionIdentifier("new-session")),
            transcript = "stale transcript",
            recordingEditorSessionIdentifier = EditorSessionIdentifier("old-session")
        )

        assertFalse(decision.insertionAllowed)
        assertFalse(decision.historyAllowed)
        assertEquals(InsertionBlockReason.STALE_EDITOR_SESSION, decision.blockReason)
    }

    @Test
    fun `does not replace a selection unless explicitly enabled`()
    {
        val blockedDecision = insertionPolicy.decide(
            EditorContext(editorSessionIdentifier, hasSelection = true, replaceSelectionEnabled = false),
            "replacement",
            editorSessionIdentifier
        )
        val allowedDecision = insertionPolicy.decide(
            EditorContext(editorSessionIdentifier, hasSelection = true, replaceSelectionEnabled = true),
            "replacement",
            editorSessionIdentifier
        )

        assertFalse(blockedDecision.insertionAllowed)
        assertTrue(allowedDecision.insertionAllowed)
    }

    @Test
    fun `editor context diagnostic rendering redacts surrounding text`()
    {
        val context = EditorContext(
            editorSessionIdentifier,
            textBeforeCursor = "private text before",
            textAfterCursor = "private text after"
        )

        assertFalse(context.toString().contains("private text"))
    }
}
