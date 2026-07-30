package com.cleardictate.input

/**
 * Keeps undo text only when the active editor's privacy decision explicitly permits retention.
 */
object InsertionRetentionPolicy
{
    fun createUndoRecord(
        editorSessionIdentifier: EditorSessionIdentifier,
        insertedText: String,
        retainTranscriptAfterInsertion: Boolean
    ): LastInsertion?
    {
        if (!retainTranscriptAfterInsertion)
        {
            return null
        }

        return LastInsertion(editorSessionIdentifier, insertedText)
    }
}
