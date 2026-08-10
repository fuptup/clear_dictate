package com.cleardictate.android.accessibility

import android.text.InputType
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Proves that the reduced accessibility metadata still blocks fields where microphone capture would be unsafe.
 */
class AccessibilityEditorSecurityInspectorTest
{
    private val inspector = AccessibilityEditorSecurityInspector()

    @Test
    fun `allows an ordinary text field`()
    {
        val decision = inspector.inspect(AccessibilityEditorSecurityMetadata(InputType.TYPE_CLASS_TEXT, false, "Message", "message_body"))

        assertTrue(decision.dictationAllowed)
    }

    @Test
    fun `blocks password pin payment and one time code fields`()
    {
        val sensitiveFields = listOf(
            AccessibilityEditorSecurityMetadata(InputType.TYPE_CLASS_TEXT, true, null, "secret"),
            AccessibilityEditorSecurityMetadata(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD, false, null, "password"),
            AccessibilityEditorSecurityMetadata(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD, false, null, "pin"),
            AccessibilityEditorSecurityMetadata(InputType.TYPE_CLASS_NUMBER, false, "Card number", "card_number"),
            AccessibilityEditorSecurityMetadata(InputType.TYPE_CLASS_NUMBER, false, "Verification code", "otp")
        )

        sensitiveFields.forEach { metadata -> assertFalse(inspector.inspect(metadata).dictationAllowed) }
    }
}
