package com.cleardictate.input

/**
 * Contains normalized security signals extracted by platform-specific editor infrastructure.
 *
 * The Android layer must derive these booleans without reading surrounding text. Keeping the
 * representation free of Android constants makes the fail-closed policy independently testable.
 */
data class EditorSecuritySignals(
    val isPassword: Boolean = false,
    val isVisiblePassword: Boolean = false,
    val isPersonalIdentificationNumber: Boolean = false,
    val isPaymentCard: Boolean = false,
    val isOneTimeCode: Boolean = false,
    val isApplicationMarkedSensitive: Boolean = false,
    val requestsPrivateInput: Boolean = false,
    val noPersonalizedLearning: Boolean = false
)

/**
 * Describes which actions are permitted for the current editor without retaining editor metadata.
 */
data class EditorSafetyDecision(
    val dictationAllowed: Boolean,
    val surroundingTextInspectionAllowed: Boolean,
    val historyAllowed: Boolean,
    val retainTranscriptAfterInsertion: Boolean
)

/**
 * Converts sensitive and private editor signals into conservative local privacy behaviour.
 */
class EditorSafetyPolicy
{
    /**
     * Blocks dictation entirely for sensitive fields and disables persistence for private fields.
     */
    fun evaluate(securitySignals: EditorSecuritySignals): EditorSafetyDecision
    {
        val isSensitive = securitySignals.isPassword ||
            securitySignals.isVisiblePassword ||
            securitySignals.isPersonalIdentificationNumber ||
            securitySignals.isPaymentCard ||
            securitySignals.isOneTimeCode ||
            securitySignals.isApplicationMarkedSensitive

        if (isSensitive)
        {
            return EditorSafetyDecision(
                dictationAllowed = false,
                surroundingTextInspectionAllowed = false,
                historyAllowed = false,
                retainTranscriptAfterInsertion = false
            )
        }

        val isPrivate = securitySignals.requestsPrivateInput || securitySignals.noPersonalizedLearning

        return EditorSafetyDecision(
            dictationAllowed = true,
            surroundingTextInspectionAllowed = !isPrivate,
            historyAllowed = !isPrivate,
            retainTranscriptAfterInsertion = !isPrivate
        )
    }
}
