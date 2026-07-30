package com.cleardictate.input

/**
 * Represents editor actions after Android-specific integer flags have been decoded.
 */
enum class SupportedEditorAction
{
    UNSPECIFIED,
    ENTER,
    DONE,
    GO,
    NEXT,
    PREVIOUS,
    SEARCH,
    SEND
}

/**
 * Resolves unspecified editor behaviour without exposing Android framework constants to the domain.
 */
object EditorActionPolicy
{
    /**
     * Preserves explicit requests and uses newlines only for fields that actually support them.
     */
    fun resolve(requestedAction: SupportedEditorAction, isMultiline: Boolean): SupportedEditorAction
    {
        if (requestedAction != SupportedEditorAction.UNSPECIFIED)
        {
            return requestedAction
        }

        return if (isMultiline)
        {
            SupportedEditorAction.ENTER
        }
        else
        {
            SupportedEditorAction.DONE
        }
    }
}
