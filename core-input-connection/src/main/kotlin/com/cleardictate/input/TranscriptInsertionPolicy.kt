package com.cleardictate.input

/**
 * Contains only the minimal, transient editor information needed for safe transcript insertion.
 */
data class EditorContext(
    val editorSessionIdentifier: EditorSessionIdentifier,
    val textBeforeCursor: String = "",
    val textAfterCursor: String = "",
    val hasSelection: Boolean = false,
    val replaceSelectionEnabled: Boolean = false,
    val isMultiline: Boolean = false,
    val isSensitive: Boolean = false,
    val isPrivate: Boolean = false
)
{
    override fun toString(): String
    {
        return "EditorContext(editorSessionIdentifier=$editorSessionIdentifier, surroundingText=<redacted>, hasSelection=$hasSelection, replaceSelectionEnabled=$replaceSelectionEnabled, " +
            "isMultiline=$isMultiline, isSensitive=$isSensitive, isPrivate=$isPrivate)"
    }
}

/**
 * Explains why insertion was refused without exposing editor or transcript contents.
 */
enum class InsertionBlockReason
{
    NONE,
    SENSITIVE_FIELD,
    STALE_EDITOR_SESSION,
    SELECTION_REPLACEMENT_DISABLED
}

/**
 * Captures the exact insertion text and independent persistence decision.
 */
data class TranscriptInsertionDecision(
    val insertionAllowed: Boolean,
    val historyAllowed: Boolean,
    val textToInsert: String,
    val blockReason: InsertionBlockReason
)
{
    override fun toString(): String
    {
        return "TranscriptInsertionDecision(insertionAllowed=$insertionAllowed, historyAllowed=$historyAllowed, textToInsert=<redacted>, blockReason=$blockReason)"
    }
}

/**
 * Applies conservative, platform-neutral spacing, privacy, and editor-session rules.
 */
class TranscriptInsertionPolicy
{
    private val punctuationThatMustNotHaveLeadingSpace = setOf('.', ',', ';', ':', '!', '?', ')', ']', '}')
    private val openingCharactersThatMustNotHaveTrailingSpace = setOf('(', '[', '{', '\n')

    /**
     * Decides insertion using only a few caller-supplied surrounding characters.
     */
    fun decide(
        editorContext: EditorContext,
        transcript: String,
        recordingEditorSessionIdentifier: EditorSessionIdentifier
    ): TranscriptInsertionDecision
    {
        if (editorContext.isSensitive)
        {
            return blocked(InsertionBlockReason.SENSITIVE_FIELD, historyAllowed = false)
        }

        if (
            recordingEditorSessionIdentifier != editorContext.editorSessionIdentifier
        )
        {
            return blocked(InsertionBlockReason.STALE_EDITOR_SESSION, historyAllowed = false)
        }

        if (editorContext.hasSelection && !editorContext.replaceSelectionEnabled)
        {
            return blocked(InsertionBlockReason.SELECTION_REPLACEMENT_DISABLED, historyAllowed = !editorContext.isPrivate)
        }

        val trimmedTranscript = transcript.trim()
        val textToInsert = applyConservativeSpacing(editorContext, trimmedTranscript)

        return TranscriptInsertionDecision(
            insertionAllowed = true,
            historyAllowed = !editorContext.isPrivate,
            textToInsert = textToInsert,
            blockReason = InsertionBlockReason.NONE
        )
    }

    private fun applyConservativeSpacing(editorContext: EditorContext, transcript: String): String
    {
        if (transcript.isEmpty())
        {
            return transcript
        }

        val characterBeforeCursor = editorContext.textBeforeCursor.lastOrNull()
        val characterAfterCursor = editorContext.textAfterCursor.firstOrNull()
        val firstTranscriptCharacter = transcript.first()
        val needsLeadingSpace =
            characterBeforeCursor != null &&
                !characterBeforeCursor.isWhitespace() &&
                !openingCharactersThatMustNotHaveTrailingSpace.contains(characterBeforeCursor) &&
                !punctuationThatMustNotHaveLeadingSpace.contains(firstTranscriptCharacter)
        val needsTrailingSpace =
            characterAfterCursor != null &&
                !characterAfterCursor.isWhitespace() &&
                !punctuationThatMustNotHaveLeadingSpace.contains(characterAfterCursor) &&
                !punctuationThatMustNotHaveLeadingSpace.contains(transcript.last())

        return buildString {
            if (needsLeadingSpace)
            {
                append(' ')
            }

            append(transcript)

            if (needsTrailingSpace)
            {
                append(' ')
            }
        }
    }

    private fun blocked(reason: InsertionBlockReason, historyAllowed: Boolean): TranscriptInsertionDecision
    {
        return TranscriptInsertionDecision(
            insertionAllowed = false,
            historyAllowed = historyAllowed,
            textToInsert = "",
            blockReason = reason
        )
    }
}
