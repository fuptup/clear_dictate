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
 * Retains only the identity, UTF-16 length, and one-way digest needed to recognize the text-change event caused by a native paste.
 */
internal data class AccessibilityPendingPaste(
    val fieldIdentity: AccessibilityFieldIdentity,
    val insertedTextLength: Int,
    val insertedTextDigest: ByteArray
)
{
    override fun toString(): String
    {
        return "AccessibilityPendingPaste(fieldIdentity=$fieldIdentity, insertedTextLength=$insertedTextLength, insertedText=<redacted>)"
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
    fun expectPaste(fieldIdentity: AccessibilityFieldIdentity, insertedText: String): AccessibilityPendingPaste?
    {
        if (insertedText.isEmpty())
        {
            return null
        }
        return AccessibilityPendingPaste(fieldIdentity, insertedText.length, digest(insertedText))
    }

    /**
     * Converts the matching text-change event into an undo record without retaining the pasted transcript or any surrounding editor content.
     */
    fun capturePaste(
        pendingPaste: AccessibilityPendingPaste,
        currentField: AccessibilityEditableText,
        insertedTextStart: Int,
        addedTextLength: Int,
        removedTextLength: Int
    ): AccessibilityInsertionUndoRecord?
    {
        if (!pendingPaste.fieldIdentity.representsSameEditor(currentField.identity) || currentField.isSensitive || removedTextLength != 0 ||
            addedTextLength != pendingPaste.insertedTextLength)
        {
            return null
        }
        val insertedTextEnd = insertedTextStart + addedTextLength
        if (insertedTextStart < 0 || insertedTextEnd > currentField.text.length)
        {
            return null
        }
        val insertedText = currentField.text.substring(insertedTextStart, insertedTextEnd)
        if (!pendingPaste.insertedTextDigest.contentEquals(digest(insertedText)))
        {
            return null
        }
        return AccessibilityInsertionUndoRecord(currentField.identity, insertedTextStart, addedTextLength, digest(insertedText))
    }

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

    /**
     * Reports whether the exact inserted range is still present, allowing a submitted composer to retire Undo without identifying individual messaging apps.
     */
    fun isUndoAvailable(record: AccessibilityInsertionUndoRecord, currentField: AccessibilityEditableText): Boolean
    {
        return unchangedInsertedTextEnd(record, currentField) != null
    }

    fun plan(record: AccessibilityInsertionUndoRecord, currentField: AccessibilityEditableText): AccessibilityUndoReplacement?
    {
        val insertedTextEnd = unchangedInsertedTextEnd(record, currentField) ?: return null
        return AccessibilityUndoReplacement(
            text = currentField.text.removeRange(record.insertedTextStart, insertedTextEnd),
            cursorPosition = record.insertedTextStart
        )
    }

    /**
     * Validates the identity, bounds, and digest shared by Undo presentation and execution so the icon cannot advertise an operation that would be refused.
     */
    private fun unchangedInsertedTextEnd(record: AccessibilityInsertionUndoRecord, currentField: AccessibilityEditableText): Int?
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
        return insertedTextEnd.takeIf { record.insertedTextDigest.contentEquals(digest(currentInsertedText)) }
    }

    private fun digest(text: String): ByteArray
    {
        return MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
    }
}
