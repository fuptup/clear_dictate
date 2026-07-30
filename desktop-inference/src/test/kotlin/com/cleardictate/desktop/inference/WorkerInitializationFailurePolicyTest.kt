package com.cleardictate.desktop.inference

import com.cleardictate.inference.InferenceFailureCategory
import com.cleardictate.inference.LocalInferenceException
import kotlinx.coroutines.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * Ensures cancellation during worker handshake or model loading remains cancellation.
 */
class WorkerInitializationFailurePolicyTest
{
    @Test
    fun `initialization cancellation is not converted into an inference fallback`()
    {
        val cancellation = CancellationException("test cancellation")

        val mappedFailure = mapWorkerInitializationFailure(cancellation) {
            LocalInferenceException(InferenceFailureCategory.PROCESS_DIED)
        }

        assertSame(cancellation, mappedFailure)
    }

    @Test
    fun `ordinary initialization failure is converted to a safe local failure`()
    {
        val mappedFailure = mapWorkerInitializationFailure(IllegalStateException("unsafe detail")) {
            LocalInferenceException(InferenceFailureCategory.PROCESS_DIED, "STARTUP_FAILED")
        }

        assertEquals(LocalInferenceException::class, mappedFailure::class)
        assertEquals("Local inference failed: PROCESS_DIED (STARTUP_FAILED)", mappedFailure.message)
    }
}
