package com.cleardictate.input

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Proves that private-editor insertion cannot leave transcript-bearing undo state.
 */
class InsertionRetentionPolicyTest
{
    @Test
    fun `private insertion creates no undo record`()
    {
        assertNull(
            InsertionRetentionPolicy.createUndoRecord(
                editorSessionIdentifier = EditorSessionIdentifier("private-editor"),
                insertedText = "Private dictated words",
                retainTranscriptAfterInsertion = false
            )
        )
    }

    @Test
    fun `standard insertion retains one exact undo record`()
    {
        val editorSessionIdentifier = EditorSessionIdentifier("standard-editor")

        assertEquals(
            LastInsertion(editorSessionIdentifier, "Standard dictated words"),
            InsertionRetentionPolicy.createUndoRecord(
                editorSessionIdentifier = editorSessionIdentifier,
                insertedText = "Standard dictated words",
                retainTranscriptAfterInsertion = true
            )
        )
    }
}
