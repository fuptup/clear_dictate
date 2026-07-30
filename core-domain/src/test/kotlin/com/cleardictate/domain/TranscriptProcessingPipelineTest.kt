package com.cleardictate.domain

import kotlinx.coroutines.CancellationException
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
    @Test
    fun `raw mode preserves exact recognizer text and derives normalized display text`() = runTest {
        val pipeline = TranscriptProcessingPipeline(polisher = RecordingPolisher("unused"))

        val result = pipeline.process("  exact   raw text  ", TranscriptMode.RAW)

        assertEquals("  exact   raw text  ", result.exactRawTranscript)
        assertEquals("exact raw text", result.selectedTranscript)
        assertEquals("Exact raw text", result.cleanTranscript)
    }

    @Test
    fun `polished mode uses validated local result`() = runTest {
        val polisher = RecordingPolisher("I think we should release it Friday.")
        val pipeline = TranscriptProcessingPipeline(polisher = polisher)

        val result = pipeline.process("Um, I think we should release it Friday.", TranscriptMode.POLISHED)

        assertEquals("I think we should release it Friday.", result.cleanTranscript)
        assertEquals("I think we should release it Friday.", result.selectedTranscript)
        assertFalse(result.usedDeterministicFallback)
        assertEquals(1, polisher.requestCount)
    }

    @Test
    fun `changed protected value falls back to exact clean transcript`() = runTest {
        val pipeline = TranscriptProcessingPipeline(polisher = RecordingPolisher("Use version 2.3, not 2.3."))

        val result = pipeline.process("Use version 2.2, not 2.3.", TranscriptMode.POLISHED)

        assertEquals("Use version 2.2, not 2.3.", result.selectedTranscript)
        assertTrue(result.usedDeterministicFallback)
        assertEquals(TranscriptFallbackReason.INTEGRITY_REJECTED, result.fallbackReason)
    }

    @Test
    fun `model failure falls back without exposing exception text`() = runTest {
        val pipeline = TranscriptProcessingPipeline(
            polisher = object : TranscriptPolisher
            {
                override suspend fun polish(request: TranscriptPolishingRequest): String
                {
                    throw IllegalStateException("transcript contents must not enter the result")
                }
            }
        )

        val result = pipeline.process("Keep this clean.", TranscriptMode.POLISHED)

        assertEquals("Keep this clean.", result.selectedTranscript)
        assertEquals(TranscriptFallbackReason.INFERENCE_FAILURE, result.fallbackReason)
        assertFalse(result.toString().contains("transcript contents must not enter the result"))
    }

    @Test
    fun `oversized prompt falls back before invoking the model`() = runTest {
        val polisher = RecordingPolisher("must not be used")
        val pipeline = TranscriptProcessingPipeline(
            polisher = polisher,
            promptTokenBudgetEstimator = PromptTokenBudgetEstimator(maximumInputCharacters = 80)
        )

        val result = pipeline.process("word ".repeat(50), TranscriptMode.POLISHED)

        assertEquals(TranscriptFallbackReason.CONTEXT_LIMIT_EXCEEDED, result.fallbackReason)
        assertEquals(0, polisher.requestCount)
    }

    @Test
    fun `coroutine cancellation propagates to the recording coordinator`() = runTest {
        val pipeline = TranscriptProcessingPipeline(
            polisher = object : TranscriptPolisher
            {
                override suspend fun polish(request: TranscriptPolishingRequest): String
                {
                    throw CancellationException("new recording started")
                }
            }
        )

        assertFailsWith<CancellationException> {
            pipeline.process("Do not retain this cancelled session.", TranscriptMode.POLISHED)
        }
    }

    private class RecordingPolisher(
        private val response: String
    ) : TranscriptPolisher
    {
        var requestCount = 0
            private set

        override suspend fun polish(request: TranscriptPolishingRequest): String
        {
            requestCount += 1
            return response
        }
    }
}
