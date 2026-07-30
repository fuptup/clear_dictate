package com.cleardictate.input

/**
 * Identifies one immutable target-editor session without retaining package or field identifiers.
 */
@JvmInline
value class EditorSessionIdentifier(val value: String)

/**
 * Prevents completed inference from crossing into a different target editor.
 */
object EditorSessionGuard
{
    /**
     * Fails closed for absent identifiers and accepts only an exact identity match.
     */
    fun matches(recordingSession: EditorSessionIdentifier, currentSession: EditorSessionIdentifier): Boolean
    {
        return recordingSession.value.isNotEmpty() &&
            currentSession.value.isNotEmpty() &&
            recordingSession == currentSession
    }
}
