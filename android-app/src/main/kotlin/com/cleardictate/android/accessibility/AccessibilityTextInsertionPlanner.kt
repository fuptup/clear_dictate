package com.cleardictate.android.accessibility

import com.cleardictate.input.EditorContext
import com.cleardictate.input.EditorSessionIdentifier
import com.cleardictate.input.TranscriptInsertionPolicy

/**
 * Identifies the focused field without retaining any of its text.
 */
internal data class AccessibilityFieldIdentity(
    val windowIdentifier: Int,
    val packageName: String,
    val className: String,
    val viewIdentifier: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
)

/**
 * Holds the current text only for the duration of one insertion calculation and redacts diagnostics.
 */
internal data class AccessibilityEditableText(
    val identity: AccessibilityFieldIdentity,
    val text: String,
    val selectionStart: Int,
    val selectionEnd: Int,
    val isSensitive: Boolean
)
{
    override fun toString(): String
    {
        return "AccessibilityEditableText(identity=$identity, text=<redacted>, selectionStart=$selectionStart, selectionEnd=$selectionEnd, isSensitive=$isSensitive)"
    }
}

/**
 * Returns the complete replacement required by AccessibilityNodeInfo.ACTION_SET_TEXT without retaining editor content.
 */
internal data class AccessibilityTextReplacement(val text: String, val cursorPosition: Int)
{
    override fun toString(): String
    {
        return "AccessibilityTextReplacement(text=<redacted>, cursorPosition=$cursorPosition)"
    }
}

/**
 * Reuses the shared spacing policy while fencing completed text to the field where recording began.
 */
internal class AccessibilityTextInsertionPlanner
{
    private val insertionPolicy = TranscriptInsertionPolicy()

    /**
     * Plans selection replacement only when the same non-sensitive editor remains focused with valid selection offsets.
     */
    fun plan(recordingField: AccessibilityFieldIdentity, currentField: AccessibilityEditableText, transcript: String): AccessibilityTextReplacement?
    {
        if (recordingField != currentField.identity || currentField.isSensitive || transcript.isBlank())
        {
            return null
        }

        val selectionStart = minOf(currentField.selectionStart, currentField.selectionEnd)
        val selectionEnd = maxOf(currentField.selectionStart, currentField.selectionEnd)
        if (selectionStart < 0 || selectionEnd > currentField.text.length)
        {
            return null
        }

        val sessionIdentifier = EditorSessionIdentifier("accessibility-focused-editor")
        val decision = insertionPolicy.decide(
            editorContext = EditorContext(
                editorSessionIdentifier = sessionIdentifier,
                textBeforeCursor = currentField.text.substring(0, selectionStart).takeLast(1),
                textAfterCursor = currentField.text.substring(selectionEnd).take(1),
                hasSelection = selectionStart != selectionEnd,
                replaceSelectionEnabled = true,
                isSensitive = false,
                isPrivate = true
            ),
            transcript = transcript,
            recordingEditorSessionIdentifier = sessionIdentifier
        )
        if (!decision.insertionAllowed)
        {
            return null
        }

        val prefix = currentField.text.substring(0, selectionStart)
        val suffix = currentField.text.substring(selectionEnd)
        return AccessibilityTextReplacement(
            text = prefix + decision.textToInsert + suffix,
            cursorPosition = prefix.length + decision.textToInsert.length
        )
    }
}
