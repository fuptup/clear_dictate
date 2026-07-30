package com.cleardictate.domain

import com.cleardictate.inference.CancellationAcknowledgement
import com.cleardictate.inference.ClientSessionIdentifier
import com.cleardictate.inference.InferenceFailureCategory
import com.cleardictate.inference.InferenceOperationContext
import com.cleardictate.inference.LocalInferenceException
import com.cleardictate.inference.OperationIdentifier
import com.cleardictate.inference.OperationPrivacy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Specifies safe transcript-mode selection and deterministic fallback around local model inference.
 */
class TranscriptProcessingPipelineTest
{
    private val operationContext = InferenceOperationContext(
        clientSessionIdentifier = ClientSessionIdentifier("test-client"),
        operationIdentifier = OperationIdentifier("test-operation"),
        privacy = OperationPrivacy.STANDARD
    )

    @Test
    fun `raw mode preserves exact recognizer text and derives normalized display text`() = runTest {
        val pipeline = TranscriptProcessingPipeline(polisher = RecordingPolisher("unused"))

        val result = pipeline.process(operationContext, "  exact   raw text  ", TranscriptMode.RAW)

        assertEquals("  exact   raw text  ", result.exactRawTranscript)
        assertEquals("exact raw text", result.selectedTranscript)
        assertEquals("Exact raw text", result.cleanTranscript)
    }

    @Test
    fun `raw mode preserves intentional line breaks while normalizing horizontal whitespace`() = runTest {
        val pipeline = TranscriptProcessingPipeline(polisher = RecordingPolisher("unused"))

        val result = pipeline.process(operationContext, "first   line\r\nsecond   line", TranscriptMode.RAW)

        assertEquals("first line\nsecond line", result.selectedTranscript)
    }

    @Test
    fun `polished mode uses validated local result`() = runTest {
        val polisher = RecordingPolisher("I think we should release it Friday.")
        val pipeline = TranscriptProcessingPipeline(polisher = polisher)

        val result = pipeline.process(operationContext, "Um, I think we should release it Friday.", TranscriptMode.POLISHED)

        assertEquals("I think we should release it Friday.", result.cleanTranscript)
        assertEquals("I think we should release it Friday.", result.selectedTranscript)
        assertFalse(result.usedDeterministicFallback)
        assertEquals(1, polisher.requestCount)
    }

    @Test
    fun `changed protected value falls back to exact clean transcript`() = runTest {
        val pipeline = TranscriptProcessingPipeline(polisher = RecordingPolisher("Use version 2.3, not 2.3."))

        val result = pipeline.process(operationContext, "Use version 2.2, not 2.3.", TranscriptMode.POLISHED)

        assertEquals("Use version 2.2, not 2.3.", result.selectedTranscript)
        assertTrue(result.usedDeterministicFallback)
        assertEquals(TranscriptFallbackReason.INTEGRITY_REJECTED, result.fallbackReason)
    }

    @Test
    fun `model failure falls back without exposing exception text`() = runTest {
        val pipeline = TranscriptProcessingPipeline(
            polisher = object : TranscriptPolisher
            {
                override suspend fun polish(operationContext: InferenceOperationContext, request: TranscriptPolishingRequest): String
                {
                    throw LocalInferenceException(InferenceFailureCategory.NATIVE_FAILURE, diagnosticCode = "NATIVE_TEST_FAILURE")
                }

                override suspend fun cancel(operationIdentifier: OperationIdentifier): CancellationAcknowledgement
                {
                    return CancellationAcknowledgement(operationIdentifier)
                }
            }
        )

        val result = pipeline.process(operationContext, "Keep this clean.", TranscriptMode.POLISHED)

        assertEquals("Keep this clean.", result.selectedTranscript)
        assertEquals(TranscriptFallbackReason.INFERENCE_FAILURE, result.fallbackReason)
        assertFalse(result.toString().contains("NATIVE_TEST_FAILURE"))
    }

    @Test
    fun `unexpected programming failure is not disguised as deterministic fallback`() = runTest {
        val pipeline = TranscriptProcessingPipeline(
            polisher = object : TranscriptPolisher
            {
                override suspend fun polish(operationContext: InferenceOperationContext, request: TranscriptPolishingRequest): String
                {
                    throw IllegalStateException("unexpected programming failure")
                }

                override suspend fun cancel(operationIdentifier: OperationIdentifier): CancellationAcknowledgement
                {
                    return CancellationAcknowledgement(operationIdentifier)
                }
            }
        )

        assertFailsWith<IllegalStateException> {
            pipeline.process(operationContext, "Keep this clean.", TranscriptMode.POLISHED)
        }
    }

    @Test
    fun `oversized prompt falls back before invoking the model`() = runTest {
        val polisher = RecordingPolisher("must not be used")
        val pipeline = TranscriptProcessingPipeline(
            polisher = polisher,
            promptTokenBudgetEstimator = PromptTokenBudgetEstimator(maximumInputCharacters = 80)
        )

        val result = pipeline.process(operationContext, "word ".repeat(50), TranscriptMode.POLISHED)

        assertEquals(TranscriptFallbackReason.CONTEXT_LIMIT_EXCEEDED, result.fallbackReason)
        assertEquals(0, polisher.requestCount)
    }

    @Test
    fun `coroutine cancellation propagates to the recording coordinator`() = runTest {
        var cancelledOperationIdentifier: OperationIdentifier? = null
        val pipeline = TranscriptProcessingPipeline(
            polisher = object : TranscriptPolisher
            {
                override suspend fun polish(operationContext: InferenceOperationContext, request: TranscriptPolishingRequest): String
                {
                    throw CancellationException("new recording started")
                }

                override suspend fun cancel(operationIdentifier: OperationIdentifier): CancellationAcknowledgement
                {
                    cancelledOperationIdentifier = operationIdentifier
                    return CancellationAcknowledgement(operationIdentifier)
                }
            }
        )

        assertFailsWith<CancellationException> {
            pipeline.process(operationContext, "Do not retain this cancelled session.", TranscriptMode.POLISHED)
        }
        assertEquals(operationContext.operationIdentifier, cancelledOperationIdentifier)
    }

    @Test
    fun `timeout requests and receives cancellation acknowledgement`() = runTest {
        var cancelledOperationIdentifier: OperationIdentifier? = null
        val pipeline = TranscriptProcessingPipeline(
            polisher = object : TranscriptPolisher
            {
                override suspend fun polish(operationContext: InferenceOperationContext, request: TranscriptPolishingRequest): String
                {
                    delay(10_000)
                    return "must not complete"
                }

                override suspend fun cancel(operationIdentifier: OperationIdentifier): CancellationAcknowledgement
                {
                    cancelledOperationIdentifier = operationIdentifier
                    return CancellationAcknowledgement(operationIdentifier)
                }
            },
            polishingTimeoutMilliseconds = 100
        )

        val result = pipeline.process(operationContext, "Keep this clean.", TranscriptMode.POLISHED)

        assertEquals(TranscriptFallbackReason.INFERENCE_TIMEOUT, result.fallbackReason)
        assertEquals(operationContext.operationIdentifier, cancelledOperationIdentifier)
    }

    @Test
    fun `mismatched cancellation acknowledgement is treated as unsafe`() = runTest {
        val pipeline = TranscriptProcessingPipeline(
            polisher = object : TranscriptPolisher
            {
                override suspend fun polish(operationContext: InferenceOperationContext, request: TranscriptPolishingRequest): String
                {
                    delay(10_000)
                    return "must not complete"
                }

                override suspend fun cancel(operationIdentifier: OperationIdentifier): CancellationAcknowledgement
                {
                    return CancellationAcknowledgement(OperationIdentifier("different-operation"))
                }
            },
            polishingTimeoutMilliseconds = 100
        )

        val result = pipeline.process(operationContext, "Keep this clean.", TranscriptMode.POLISHED)

        assertEquals(TranscriptFallbackReason.CANCELLATION_NOT_ACKNOWLEDGED, result.fallbackReason)
    }

    private class RecordingPolisher(
        private val response: String
    ) : TranscriptPolisher
    {
        var requestCount = 0
            private set

        override suspend fun polish(operationContext: InferenceOperationContext, request: TranscriptPolishingRequest): String
        {
            requestCount += 1
            return response
        }

        override suspend fun cancel(operationIdentifier: OperationIdentifier): CancellationAcknowledgement
        {
            return CancellationAcknowledgement(operationIdentifier)
        }
    }
}
