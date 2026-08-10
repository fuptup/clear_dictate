package com.cleardictate.android.accessibility

import java.security.MessageDigest

/**
 * Retains only the location and one-way digest needed to remove one unchanged inserted segment.
 */
internal data class AccessibilityInsertionUndoRecord(
    val fieldIdentity: AccessibilityFieldIdentity,
    val insertedTextStart: Int,
    val insertedTextLength: Int,
    val insertedTextDigest: ByteArray
)
{
    override fun toString(): String
    {
        return "AccessibilityInsertionUndoRecord(fieldIdentity=$fieldIdentity, insertedTextStart=$insertedTextStart, insertedTextLength=$insertedTextLength, insertedText=<redacted>)"
    }
}

/**
 * Contains the complete field value and restored cursor required by Android's set-text action.
 */
internal data class AccessibilityUndoReplacement(val text: String, val cursorPosition: Int)
{
    override fun toString(): String
    {
        return "AccessibilityUndoReplacement(text=<redacted>, cursorPosition=$cursorPosition)"
    }
}

/**
 * Captures and verifies a bounded undo without retaining dictated or surrounding field text.
 */
internal class AccessibilityInsertionUndoPlanner
{
    fun capture(fieldIdentity: AccessibilityFieldIdentity, replacement: AccessibilityTextReplacement): AccessibilityInsertionUndoRecord?
    {
        if (replacement.insertedTextStart < 0 || replacement.insertedTextEnd <= replacement.insertedTextStart || replacement.insertedTextEnd > replacement.text.length)
        {
            return null
        }
        val insertedText = replacement.text.substring(replacement.insertedTextStart, replacement.insertedTextEnd)
        return AccessibilityInsertionUndoRecord(
            fieldIdentity = fieldIdentity,
            insertedTextStart = replacement.insertedTextStart,
            insertedTextLength = insertedText.length,
            insertedTextDigest = digest(insertedText)
        )
    }

    fun plan(record: AccessibilityInsertionUndoRecord, currentField: AccessibilityEditableText): AccessibilityUndoReplacement?
    {
        if (!record.fieldIdentity.representsSameEditor(currentField.identity) || currentField.isSensitive)
        {
            return null
        }
        val insertedTextEnd = record.insertedTextStart + record.insertedTextLength
        if (record.insertedTextStart < 0 || insertedTextEnd > currentField.text.length)
        {
            return null
        }
        val currentInsertedText = currentField.text.substring(record.insertedTextStart, insertedTextEnd)
        if (!record.insertedTextDigest.contentEquals(digest(currentInsertedText)))
        {
            return null
        }
        return AccessibilityUndoReplacement(
            text = currentField.text.removeRange(record.insertedTextStart, insertedTextEnd),
            cursorPosition = record.insertedTextStart
        )
    }

    private fun digest(text: String): ByteArray
    {
        return MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
    }
}
