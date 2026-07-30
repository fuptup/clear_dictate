package com.cleardictate.input

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Verifies that sensitive and private editor signals fail closed before surrounding text is read.
 */
class EditorSafetyPolicyTest
{
    private val policy = EditorSafetyPolicy()

    @Test
    fun `blocks every explicitly sensitive field category`()
    {
        val sensitiveContexts = listOf(
            EditorSecuritySignals(isPassword = true),
            EditorSecuritySignals(isVisiblePassword = true),
            EditorSecuritySignals(isPersonalIdentificationNumber = true),
            EditorSecuritySignals(isPaymentCard = true),
            EditorSecuritySignals(isOneTimeCode = true),
            EditorSecuritySignals(isApplicationMarkedSensitive = true)
        )

        sensitiveContexts.forEach { securitySignals ->
            val decision = policy.evaluate(securitySignals)

            assertFalse(decision.dictationAllowed)
            assertFalse(decision.surroundingTextInspectionAllowed)
            assertFalse(decision.historyAllowed)
        }
    }

    @Test
    fun `private and no-learning editors allow dictation but prohibit persistence`()
    {
        val privateDecision = policy.evaluate(EditorSecuritySignals(requestsPrivateInput = true))
        val noLearningDecision = policy.evaluate(EditorSecuritySignals(noPersonalizedLearning = true))

        assertTrue(privateDecision.dictationAllowed)
        assertFalse(privateDecision.historyAllowed)
        assertFalse(privateDecision.retainTranscriptAfterInsertion)
        assertTrue(noLearningDecision.dictationAllowed)
        assertFalse(noLearningDecision.historyAllowed)
        assertFalse(noLearningDecision.retainTranscriptAfterInsertion)
    }
}
