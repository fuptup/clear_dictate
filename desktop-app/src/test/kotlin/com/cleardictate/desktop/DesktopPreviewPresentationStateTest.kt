package com.cleardictate.desktop

import com.cleardictate.domain.ProcessedTranscript
import com.cleardictate.domain.TranscriptFallbackReason
import com.cleardictate.domain.TranscriptMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Prevents the preview from presenting stale or user-edited text as a validated pipeline result.
 */
class DesktopPreviewPresentationStateTest
{
    @Test
    fun `source change invalidates previously processed output`()
    {
        val processedState = DesktopPreviewPresentationState(rawTranscript = "old source")
            .withProcessedTranscript(successfulPolishedTranscript())

        val updatedState = processedState.withRawTranscript("new source")

        assertEquals("", updatedState.outputTranscript)
        assertEquals("", updatedState.cleanTranscript)
        assertEquals(TranscriptFallbackReason.NONE, updatedState.fallbackReason)
        assertTrue(updatedState.statusMessage.contains("Process again"))
    }

    @Test
    fun `mode change invalidates output so Raw cannot copy a prior Polished result`()
    {
        val processedState = DesktopPreviewPresentationState(
            rawTranscript = "source",
            selectedMode = TranscriptMode.POLISHED
        ).withProcessedTranscript(successfulPolishedTranscript())

        val rawState = processedState.withSelectedMode(TranscriptMode.RAW)

        assertEquals("", rawState.outputTranscript)
        assertEquals(TranscriptMode.RAW, rawState.selectedMode)
        assertTrue(rawState.statusMessage.contains("Process again"))
    }

    @Test
    fun `manual output edit clears validation and fallback claims`()
    {
        val processedState = DesktopPreviewPresentationState(
            rawTranscript = "source",
            selectedMode = TranscriptMode.POLISHED
        ).withProcessedTranscript(successfulPolishedTranscript())

        val editedState = processedState.withManuallyEditedOutput("Manually edited text.")

        assertTrue(editedState.outputWasManuallyEdited)
        assertEquals(TranscriptFallbackReason.NONE, editedState.fallbackReason)
        assertTrue(editedState.statusMessage.contains("no longer applies"))
        assertFalse(editedState.statusMessage.contains("passed"))
    }

    private fun successfulPolishedTranscript(): ProcessedTranscript
    {
        return ProcessedTranscript(
            exactRawTranscript = "source",
            normalizedRawTranscript = "source",
            cleanTranscript = "Source",
            polishedTranscript = "Source.",
            selectedTranscript = "Source.",
            selectedMode = TranscriptMode.POLISHED,
            usedDeterministicFallback = false,
            fallbackReason = TranscriptFallbackReason.NONE
        )
    }
}
