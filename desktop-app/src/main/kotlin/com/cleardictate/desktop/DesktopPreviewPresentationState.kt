package com.cleardictate.desktop

import com.cleardictate.domain.ProcessedTranscript
import com.cleardictate.domain.TranscriptFallbackReason
import com.cleardictate.domain.TranscriptMode

/**
 * Keeps every displayed result explicitly associated with the source and mode that produced it.
 */
data class DesktopPreviewPresentationState(
    val rawTranscript: String = "",
    val selectedMode: TranscriptMode = TranscriptMode.CLEAN,
    val outputTranscript: String = "",
    val cleanTranscript: String = "",
    val statusMessage: String = "Ready for local text-pipeline testing.",
    val fallbackReason: TranscriptFallbackReason = TranscriptFallbackReason.NONE,
    val outputWasManuallyEdited: Boolean = false
)
{
    fun withRawTranscript(updatedRawTranscript: String): DesktopPreviewPresentationState
    {
        if (updatedRawTranscript == rawTranscript)
        {
            return this
        }

        return copy(
            rawTranscript = updatedRawTranscript,
            outputTranscript = "",
            cleanTranscript = "",
            statusMessage = "Source changed. Process again before copying an output.",
            fallbackReason = TranscriptFallbackReason.NONE,
            outputWasManuallyEdited = false
        )
    }

    fun withSelectedMode(updatedMode: TranscriptMode): DesktopPreviewPresentationState
    {
        if (updatedMode == selectedMode)
        {
            return this
        }

        return copy(
            selectedMode = updatedMode,
            outputTranscript = "",
            cleanTranscript = "",
            statusMessage = "Processing mode changed. Process again before copying an output.",
            fallbackReason = TranscriptFallbackReason.NONE,
            outputWasManuallyEdited = false
        )
    }

    fun withProcessedTranscript(processedTranscript: ProcessedTranscript): DesktopPreviewPresentationState
    {
        return copy(
            outputTranscript = processedTranscript.selectedTranscript,
            cleanTranscript = processedTranscript.cleanTranscript,
            statusMessage = processedTranscript.completionStatusMessage(),
            fallbackReason = processedTranscript.fallbackReason,
            outputWasManuallyEdited = false
        )
    }

    fun withManuallyEditedOutput(updatedOutputTranscript: String): DesktopPreviewPresentationState
    {
        if (updatedOutputTranscript == outputTranscript)
        {
            return this
        }

        return copy(
            outputTranscript = updatedOutputTranscript,
            statusMessage = "Output edited manually. The semantic-integrity result no longer applies to this text.",
            fallbackReason = TranscriptFallbackReason.NONE,
            outputWasManuallyEdited = true
        )
    }

    fun withStatus(updatedStatusMessage: String): DesktopPreviewPresentationState
    {
        return copy(statusMessage = updatedStatusMessage)
    }

    fun withProcessingStatus(updatedStatusMessage: String): DesktopPreviewPresentationState
    {
        return copy(
            statusMessage = updatedStatusMessage,
            fallbackReason = TranscriptFallbackReason.NONE,
            outputWasManuallyEdited = false
        )
    }

    fun cleared(): DesktopPreviewPresentationState
    {
        return DesktopPreviewPresentationState(
            selectedMode = selectedMode,
            statusMessage = "Text cleared from this unsaved preview session."
        )
    }
}

private fun ProcessedTranscript.completionStatusMessage(): String
{
    return when
    {
        usedDeterministicFallback && fallbackReason == TranscriptFallbackReason.INFERENCE_FAILURE ->
            "Local inference failed; the worker was discarded and the deterministic Clean result was used. The next Polished request will start a fresh worker."
        usedDeterministicFallback -> "Processing completed with deterministic fallback."
        selectedMode == TranscriptMode.POLISHED -> "Local polishing completed and passed semantic-integrity checks."
        else -> "${selectedMode.displayName()} processing completed locally."
    }
}

fun TranscriptMode.displayName(): String
{
    return name.lowercase().replaceFirstChar(Char::uppercase)
}

fun TranscriptFallbackReason.displayName(): String
{
    return name.lowercase().replace('_', ' ')
}
