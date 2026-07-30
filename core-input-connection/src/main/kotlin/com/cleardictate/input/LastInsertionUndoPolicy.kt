package com.cleardictate.input

/**
 * Records only the most recent ClearDictate insertion and its editor-session identity.
 */
data class LastInsertion(
    val editorSessionIdentifier: EditorSessionIdentifier,
    val insertedText: String
)
{
    override fun toString(): String
    {
        return "LastInsertion(editorSessionIdentifier=$editorSessionIdentifier, insertedText=<redacted>)"
    }
}

/**
 * Authorizes a narrow backward deletion only after exact surrounding-text verification.
 */
class LastInsertionUndoPolicy
{
    /**
     * Returns the exact UTF-16 character count to delete, or null when any safety check fails.
     */
    fun charactersToDelete(
        textBeforeCursor: String,
        currentEditorSessionIdentifier: EditorSessionIdentifier,
        lastInsertion: LastInsertion?
    ): Int?
    {
        if (lastInsertion == null || lastInsertion.insertedText.isEmpty())
        {
            return null
        }

        if (!EditorSessionGuard.matches(lastInsertion.editorSessionIdentifier, currentEditorSessionIdentifier))
        {
            return null
        }

        if (!textBeforeCursor.endsWith(lastInsertion.insertedText))
        {
            return null
        }

        return lastInsertion.insertedText.length
    }
}
