package com.cleardictate.android.input

import android.text.InputType
import android.view.inputmethod.EditorInfo
import com.cleardictate.input.EditorSecuritySignals
import java.util.Locale

/**
 * Translates Android editor metadata into the small platform-neutral security representation.
 *
 * This inspector deliberately does not access InputConnection or surrounding text. The caller can
 * therefore run the security policy before deciding whether any field contents may be inspected.
 */
class AndroidEditorInfoInspector
{
    /**
     * Detects security signals that Android exposes directly, plus conservative explicit hint terms.
     */
    fun inspectSecuritySignals(editorInfo: EditorInfo): EditorSecuritySignals
    {
        val inputClass = editorInfo.inputType and InputType.TYPE_MASK_CLASS
        val inputVariation = editorInfo.inputType and InputType.TYPE_MASK_VARIATION
        val isTextPassword = inputClass == InputType.TYPE_CLASS_TEXT &&
            inputVariation in textPasswordVariations
        val isVisiblePassword = inputClass == InputType.TYPE_CLASS_TEXT &&
            inputVariation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        val isNumericPassword = inputClass == InputType.TYPE_CLASS_NUMBER &&
            inputVariation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
        val normalizedHint = editorInfo.hintText?.toString()?.lowercase(Locale.ROOT).orEmpty()
        val normalizedPrivateOptions = editorInfo.privateImeOptions?.lowercase(Locale.ROOT).orEmpty()

        return EditorSecuritySignals(
            isPassword = isTextPassword || isNumericPassword,
            isVisiblePassword = isVisiblePassword,
            isPersonalIdentificationNumber = isNumericPassword || containsAny(normalizedHint, personalIdentificationNumberHintTerms),
            isPaymentCard = containsAny(normalizedHint, paymentCardHintTerms),
            isOneTimeCode = containsAny(normalizedHint, oneTimeCodeHintTerms),
            isApplicationMarkedSensitive = normalizedPrivateOptions.contains("sensitive=true"),
            requestsPrivateInput = containsAny(normalizedPrivateOptions, privateInputOptionTerms),
            noPersonalizedLearning = editorInfo.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0
        )
    }

    private fun containsAny(normalizedValue: String, terms: Set<String>): Boolean
    {
        return terms.any { term ->
            Regex("""(?<![\p{L}\p{N}])${Regex.escape(term)}(?![\p{L}\p{N}])""").containsMatchIn(normalizedValue)
        }
    }

    private companion object
    {
        val textPasswordVariations = setOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
        )
        val personalIdentificationNumberHintTerms = setOf("pin", "personal identification number")
        val paymentCardHintTerms = setOf("card number", "credit card", "debit card", "cvv", "cvc")
        val oneTimeCodeHintTerms = setOf("one-time code", "one time code", "otp", "verification code", "authentication code")
        val privateInputOptionTerms = setOf("incognito", "private=true", "privatemode=true", "private_mode=true")
    }
}
