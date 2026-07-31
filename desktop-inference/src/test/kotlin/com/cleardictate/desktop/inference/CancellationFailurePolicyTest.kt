package com.cleardictate.desktop.inference

import com.cleardictate.inference.InferenceFailureCategory
import com.cleardictate.inference.LocalInferenceException
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Verifies when a failed cancellation makes the reusable worker unsafe.
 */
class CancellationFailurePolicyTest
{
    @Test
    fun `keeps worker alive when operation result won cancellation race`()
    {
        assertFalse(
            cancellationFailureRequiresWorkerClose(
                LocalInferenceException(
                    InferenceFailureCategory.CANCELLATION_NOT_ACKNOWLEDGED,
                    "OPERATION_ALREADY_TERMINAL"
                )
            )
        )
    }

    @Test
    fun `closes worker after cancellation timeout`()
    {
        assertTrue(
            cancellationFailureRequiresWorkerClose(
                LocalInferenceException(
                    InferenceFailureCategory.TIMEOUT,
                    "CANCELLATION_TIMEOUT"
                )
            )
        )
    }
}
