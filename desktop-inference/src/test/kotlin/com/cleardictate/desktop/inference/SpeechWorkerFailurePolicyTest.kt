package com.cleardictate.desktop.inference

import com.cleardictate.inference.InferenceFailureCategory
import com.cleardictate.inference.LocalInferenceException
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpeechWorkerFailurePolicyTest
{
    @Test
    fun `capture operation errors leave the verified speech model reusable`()
    {
        assertFalse(
            speechOperationFailureRequiresWorkerClose(
                LocalInferenceException(
                    InferenceFailureCategory.REQUEST_REJECTED,
                    "MICROPHONE_PRIVACY_BLOCKED"
                )
            )
        )
        assertFalse(
            speechOperationFailureRequiresWorkerClose(
                LocalInferenceException(
                    InferenceFailureCategory.NATIVE_FAILURE,
                    "CAPTURE_FAILED"
                )
            )
        )
    }

    @Test
    fun `timeouts and process protocol failures close uncertain worker state`()
    {
        assertTrue(
            speechOperationFailureRequiresWorkerClose(
                LocalInferenceException(InferenceFailureCategory.TIMEOUT)
            )
        )
        assertTrue(
            speechOperationFailureRequiresWorkerClose(
                LocalInferenceException(InferenceFailureCategory.PROCESS_DIED)
            )
        )
        assertTrue(
            speechOperationFailureRequiresWorkerClose(
                LocalInferenceException(InferenceFailureCategory.PROTOCOL_FAILURE)
            )
        )
    }
}
