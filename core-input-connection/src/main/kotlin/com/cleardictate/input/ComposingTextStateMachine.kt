package com.cleardictate.input

/**
 * Enumerates every lifecycle boundary that invalidates temporary partial recognition text.
 */
enum class ComposingTextInvalidation
{
    USER_CANCELLED,
    INPUT_CONNECTION_LOST,
    TARGET_EDITOR_CHANGED,
    INPUT_VIEW_HIDDEN,
    KEYBOARD_SWITCHED,
    DEVICE_LOCKED,
    INFERENCE_FAILED,
    FINAL_TEXT_COMMITTED
}

/**
 * Tells platform infrastructure whether it must actively remove a composing region.
 */
sealed interface ComposingTextCommand
{
    data object None : ComposingTextCommand
    data object RemoveFromEditor : ComposingTextCommand
}

/**
 * Tracks transient partial text without ever treating it as permanent editor content.
 */
class ComposingTextStateMachine
{
    var currentText: String? = null
        private set

    /**
     * Replaces the complete transient value; blank recognition clears local state.
     */
    fun update(partialTranscript: String): ComposingTextCommand
    {
        val previousTextWasPresent = currentText != null
        currentText = partialTranscript.takeIf { transcript -> transcript.isNotBlank() }

        if (currentText == null && previousTextWasPresent)
        {
            return ComposingTextCommand.RemoveFromEditor
        }

        return ComposingTextCommand.None
    }

    /**
     * Clears local state and requests editor cleanup exactly once.
     */
    fun invalidate(invalidation: ComposingTextInvalidation): ComposingTextCommand
    {
        if (currentText == null)
        {
            return ComposingTextCommand.None
        }

        currentText = null
        return ComposingTextCommand.RemoveFromEditor
    }
}
