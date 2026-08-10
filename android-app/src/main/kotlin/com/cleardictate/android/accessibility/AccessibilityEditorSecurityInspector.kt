package com.cleardictate.android.accessibility

import android.text.InputType
import com.cleardictate.input.EditorSafetyDecision
import com.cleardictate.input.EditorSafetyPolicy
import com.cleardictate.input.EditorSecuritySignals
import java.util.Locale

/**
 * Contains only the accessibility metadata needed to reject sensitive editable fields before recording.
 */
internal data class AccessibilityEditorSecurityMetadata(
    val inputType: Int,
    val isPassword: Boolean,
    val hintText: String?,
    val viewIdentifier: String?
)

/**
 * Converts the smaller AccessibilityNodeInfo signal set into the shared fail-closed editor policy.
 */
internal class AccessibilityEditorSecurityInspector
{
    private val safetyPolicy = EditorSafetyPolicy()

    /**
     * Treats explicit password input types and sensitive field labels as fields where dictation must not start.
     */
    fun inspect(metadata: AccessibilityEditorSecurityMetadata): EditorSafetyDecision
    {
        val inputClass = metadata.inputType and InputType.TYPE_MASK_CLASS
        val inputVariation = metadata.inputType and InputType.TYPE_MASK_VARIATION
        val normalizedLabel = listOfNotNull(metadata.hintText, metadata.viewIdentifier)
            .joinToString(separator = " ")
            .replace('_', ' ')
            .replace('-', ' ')
            .lowercase(Locale.ROOT)
        val isTextPassword = inputClass == InputType.TYPE_CLASS_TEXT && inputVariation in textPasswordVariations
        val isVisiblePassword = inputClass == InputType.TYPE_CLASS_TEXT && inputVariation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        val isNumericPassword = inputClass == InputType.TYPE_CLASS_NUMBER && inputVariation == InputType.TYPE_NUMBER_VARIATION_PASSWORD

        return safetyPolicy.evaluate(
            EditorSecuritySignals(
                isPassword = metadata.isPassword || isTextPassword || isNumericPassword,
                isVisiblePassword = isVisiblePassword,
                isPersonalIdentificationNumber = isNumericPassword || containsAny(normalizedLabel, personalIdentificationNumberTerms),
                isPaymentCard = containsAny(normalizedLabel, paymentCardTerms),
                isOneTimeCode = containsAny(normalizedLabel, oneTimeCodeTerms)
            )
        )
    }

    private fun containsAny(normalizedValue: String, terms: Set<String>): Boolean
    {
        return terms.any { term -> Regex("""(?<![\p{L}\p{N}])${Regex.escape(term)}(?![\p{L}\p{N}])""").containsMatchIn(normalizedValue) }
    }

    private companion object
    {
        val textPasswordVariations = setOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
        )
        val personalIdentificationNumberTerms = setOf("pin", "personal identification number")
        val paymentCardTerms = setOf("card number", "credit card", "debit card", "cvv", "cvc")
        val oneTimeCodeTerms = setOf("one-time code", "one time code", "otp", "verification code", "authentication code")
    }
}
