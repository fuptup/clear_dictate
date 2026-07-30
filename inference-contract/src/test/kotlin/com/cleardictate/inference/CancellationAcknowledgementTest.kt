package com.cleardictate.inference

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Verifies that cancellation acknowledgement cannot be confused across operations.
 */
class CancellationAcknowledgementTest
{
    @Test
    fun `acknowledgement retains the exact cancelled operation identity`()
    {
        val operationIdentifier = OperationIdentifier("operation-19")
        val acknowledgement = CancellationAcknowledgement(operationIdentifier)

        assertEquals(operationIdentifier, acknowledgement.operationIdentifier)
    }

    @Test
    fun `failure exceptions expose only a fixed non-sensitive category`()
    {
        val exception = LocalInferenceException(InferenceFailureCategory.NATIVE_FAILURE)

        assertEquals("Local inference failed: NATIVE_FAILURE", exception.message)
        assertFailsWith<IllegalArgumentException> {
            LocalInferenceException(InferenceFailureCategory.NATIVE_FAILURE, diagnosticCode = "contains spaces")
        }
    }
}
