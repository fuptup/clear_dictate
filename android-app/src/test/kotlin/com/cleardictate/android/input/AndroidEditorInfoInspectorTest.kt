package com.cleardictate.android.input

import android.text.InputType
import android.view.inputmethod.EditorInfo
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Verifies translation from Android editor metadata into platform-neutral privacy signals.
 */
class AndroidEditorInfoInspectorTest
{
    private val inspector = AndroidEditorInfoInspector()

    @Test
    fun `detects text password variations`()
    {
        val passwordVariations = listOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
        )

        passwordVariations.forEach { variation ->
            val editorInfo = EditorInfo().apply {
                inputType = InputType.TYPE_CLASS_TEXT or variation
            }

            assertTrue(inspector.inspectSecuritySignals(editorInfo).isPassword)
        }
    }

    @Test
    fun `detects numeric password as personal identification number`()
    {
        val editorInfo = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }

        val signals = inspector.inspectSecuritySignals(editorInfo)

        assertTrue(signals.isPassword)
        assertTrue(signals.isPersonalIdentificationNumber)
    }

    @Test
    fun `detects explicit payment and one-time-code hints without reading field contents`()
    {
        val paymentEditor = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hintText = "Card number"
        }
        val oneTimeCodeEditor = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hintText = "One-time code"
        }

        assertTrue(inspector.inspectSecuritySignals(paymentEditor).isPaymentCard)
        assertTrue(inspector.inspectSecuritySignals(oneTimeCodeEditor).isOneTimeCode)
    }

    @Test
    fun `respects no personalized learning and private editor options`()
    {
        val noLearningEditor = EditorInfo().apply {
            imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        }
        val privateEditor = EditorInfo().apply {
            privateImeOptions = "example.privateMode=true"
        }

        assertTrue(inspector.inspectSecuritySignals(noLearningEditor).noPersonalizedLearning)
        assertTrue(inspector.inspectSecuritySignals(privateEditor).requestsPrivateInput)
    }

    @Test
    fun `ordinary text field remains eligible for dictation`()
    {
        val editorInfo = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL
            hintText = "Write a message"
        }

        val signals = inspector.inspectSecuritySignals(editorInfo)

        assertFalse(signals.isPassword)
        assertFalse(signals.isPaymentCard)
        assertFalse(signals.isOneTimeCode)
        assertFalse(signals.requestsPrivateInput)
    }

    @Test
    fun `shipping hint is not misclassified as personal identification number`()
    {
        val editorInfo = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT
            hintText = "Shipping address"
        }

        assertFalse(inspector.inspectSecuritySignals(editorInfo).isPersonalIdentificationNumber)
    }
}
