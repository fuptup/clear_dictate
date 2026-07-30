package com.cleardictate.domain

import com.cleardictate.inference.CancellationAcknowledgement
import com.cleardictate.inference.InferenceFailureCategory
import com.cleardictate.inference.InferenceOperationContext
import com.cleardictate.inference.LocalInferenceException
import com.cleardictate.inference.OperationIdentifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Selects how far one finalized recognizer transcript progresses through the local pipeline.
 */
enum class TranscriptMode
{
    RAW,
    CLEAN,
    POLISHED
}
/**
 * Pins deterministic generation settings while allowing benchmark-selected thread counts.
 */
data class TranscriptPolishingConfiguration(
    val contextSizeTokens: Int = 2048,
    val maximumGeneratedTokens: Int = 256,
    val temperature: Float = 0.0f,
    val topP: Float = 1.0f,
    val deterministicSeed: Int = 42,
    val threadCount: Int = 4
)

/**
 * Keeps trusted instructions separate from the untrusted transcript and native generation settings.
 */
data class TranscriptPolishingRequest(
    val systemInstruction: String,
    val userMessage: String,
    val configuration: TranscriptPolishingConfiguration = TranscriptPolishingConfiguration()
)
{
    override fun toString(): String
    {
        return "TranscriptPolishingRequest(systemInstruction=<redacted>, userMessage=<redacted>, configuration=$configuration)"
    }
}

/**
 * Isolates every platform-specific local language-model implementation behind a suspending boundary.
 */
interface TranscriptPolisher
{
    suspend fun polish(operationContext: InferenceOperationContext, request: TranscriptPolishingRequest): String

    /**
     * Requests native cancellation without waiting behind the active inference operation.
     */
    suspend fun cancel(operationIdentifier: OperationIdentifier): CancellationAcknowledgement
}

/**
 * Builds the exact editing instruction while encoding transcript delimiter characters as data.
 */
object TranscriptPolishingPromptBuilder
{
    private const val SYSTEM_INSTRUCTION = """You edit spoken transcripts into clear written English.

Remove hesitation fillers, abandoned starts, accidental repetitions, and verbal clutter.

Improve punctuation and sentence structure only where required for readability.

Preserve the speaker's intended meaning exactly.

Preserve all names, numbers, dates, measurements, prices, identifiers, technical terms, negations, qualifications, uncertainty, and corrections.

Do not summarize.

Do not add facts.

Do not answer the transcript.

Do not explain your edits.

Return only the edited transcript."""

    /**
     * Returns distinct system and user messages so the native chat template preserves role authority.
     */
    fun build(cleanTranscript: String): TranscriptPolishingRequest
    {
        val encodedTranscript = encodeTranscriptAsXmlText(cleanTranscript)
        val userMessage = """Edit this transcript:

<transcript>
$encodedTranscript
</transcript>"""

        return TranscriptPolishingRequest(
            systemInstruction = SYSTEM_INSTRUCTION,
            userMessage = userMessage
        )
    }

    private fun encodeTranscriptAsXmlText(transcript: String): String
    {
        return transcript
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
    }
}

/**
 * Conservatively prevents requests that cannot fit without silently truncating source text.
 *
 * The native tokenizer remains authoritative. This character ceiling is an inexpensive preflight;
 * the native adapter must also reject any request that exceeds its exact token budget.
 */
class PromptTokenBudgetEstimator(
    private val maximumInputCharacters: Int = 5_000
)
{
    fun canProcess(request: TranscriptPolishingRequest): Boolean
    {
        return request.systemInstruction.length + request.userMessage.length <= maximumInputCharacters
    }
}

/**
 * Records a safe, transcript-free explanation when Polished mode returns deterministic Clean text.
 */
enum class TranscriptFallbackReason
{
    NONE,
    CONTEXT_LIMIT_EXCEEDED,
    INTEGRITY_REJECTED,
    INFERENCE_TIMEOUT,
    CANCELLATION_NOT_ACKNOWLEDGED,
    INFERENCE_FAILURE
}

/**
 * Preserves every transcript stage instead of silently replacing recognizer output.
 */
data class ProcessedTranscript(
    val exactRawTranscript: String,
    val normalizedRawTranscript: String,
    val cleanTranscript: String,
    val polishedTranscript: String?,
    val selectedTranscript: String,
    val selectedMode: TranscriptMode,
    val usedDeterministicFallback: Boolean,
    val fallbackReason: TranscriptFallbackReason
)
{
    override fun toString(): String
    {
        return "ProcessedTranscript(transcripts=<redacted>, selectedMode=$selectedMode, usedDeterministicFallback=$usedDeterministicFallback, fallbackReason=$fallbackReason)"
    }
}

/**
 * Coordinates deterministic cleanup, optional local polishing, validation, and fail-closed fallback.
 */
class TranscriptProcessingPipeline(
    private val polisher: TranscriptPolisher,
    private val cleaner: DeterministicDisfluencyCleaner = DeterministicDisfluencyCleaner(),
    private val integrityValidator: TranscriptIntegrityValidator = TranscriptIntegrityValidator(),
    private val promptTokenBudgetEstimator: PromptTokenBudgetEstimator = PromptTokenBudgetEstimator(),
    private val polishingTimeoutMilliseconds: Long = 15_000,
    private val cancellationAcknowledgementTimeoutMilliseconds: Long = 1_000
)
{
    /**
     * Processes only finalized recognizer text. Partial recognition must never call this method.
     */
    suspend fun process(
        operationContext: InferenceOperationContext,
        exactRawTranscript: String,
        mode: TranscriptMode
    ): ProcessedTranscript
    {
        val normalizedRawTranscript = normalizeRawWhitespace(exactRawTranscript)
        val cleanupResult = cleaner.clean(exactRawTranscript)
        val cleanTranscript = cleanupResult.cleanedTranscript

        if (mode == TranscriptMode.RAW)
        {
            return successfulResult(
                exactRawTranscript = exactRawTranscript,
                normalizedRawTranscript = normalizedRawTranscript,
                cleanTranscript = cleanTranscript,
                polishedTranscript = null,
                selectedTranscript = normalizedRawTranscript,
                selectedMode = mode
            )
        }

        if (mode == TranscriptMode.CLEAN)
        {
            return successfulResult(
                exactRawTranscript = exactRawTranscript,
                normalizedRawTranscript = normalizedRawTranscript,
                cleanTranscript = cleanTranscript,
                polishedTranscript = null,
                selectedTranscript = cleanTranscript,
                selectedMode = mode
            )
        }

        return polishWithFallback(operationContext, exactRawTranscript, normalizedRawTranscript, cleanTranscript)
    }

    private suspend fun polishWithFallback(
        operationContext: InferenceOperationContext,
        exactRawTranscript: String,
        normalizedRawTranscript: String,
        cleanTranscript: String
    ): ProcessedTranscript
    {
        val request = TranscriptPolishingPromptBuilder.build(cleanTranscript)

        if (!promptTokenBudgetEstimator.canProcess(request))
        {
            return fallbackResult(
                exactRawTranscript,
                normalizedRawTranscript,
                cleanTranscript,
                TranscriptFallbackReason.CONTEXT_LIMIT_EXCEEDED
            )
        }

        val polishedTranscript = try
        {
            withTimeout(polishingTimeoutMilliseconds)
            {
                polisher.polish(operationContext, request)
            }
        }
        catch (_: TimeoutCancellationException)
        {
            val cancellationAcknowledged = requestCancellation(operationContext.operationIdentifier)

            return fallbackResult(
                exactRawTranscript,
                normalizedRawTranscript,
                cleanTranscript,
                if (cancellationAcknowledged)
                {
                    TranscriptFallbackReason.INFERENCE_TIMEOUT
                }
                else
                {
                    TranscriptFallbackReason.CANCELLATION_NOT_ACKNOWLEDGED
                }
            )
        }
        catch (cancellationException: CancellationException)
        {
            val cancellationAcknowledged = requestCancellation(operationContext.operationIdentifier)

            if (!cancellationAcknowledged)
            {
                cancellationException.addSuppressed(
                    LocalInferenceException(InferenceFailureCategory.CANCELLATION_NOT_ACKNOWLEDGED)
                )
            }

            throw cancellationException
        }
        catch (_: LocalInferenceException)
        {
            return fallbackResult(
                exactRawTranscript,
                normalizedRawTranscript,
                cleanTranscript,
                TranscriptFallbackReason.INFERENCE_FAILURE
            )
        }

        val integrityResult = integrityValidator.validate(cleanTranscript, polishedTranscript)

        if (!integrityResult.accepted)
        {
            return fallbackResult(
                exactRawTranscript,
                normalizedRawTranscript,
                cleanTranscript,
                TranscriptFallbackReason.INTEGRITY_REJECTED
            )
        }

        return successfulResult(
            exactRawTranscript = exactRawTranscript,
            normalizedRawTranscript = normalizedRawTranscript,
            cleanTranscript = cleanTranscript,
            polishedTranscript = polishedTranscript,
            selectedTranscript = polishedTranscript,
            selectedMode = TranscriptMode.POLISHED
        )
    }

    /**
     * Requests cancellation in a non-cancellable scope so parent cancellation cannot skip native cleanup.
     */
    private suspend fun requestCancellation(operationIdentifier: OperationIdentifier): Boolean
    {
        return withContext(NonCancellable)
        {
            try
            {
                withTimeout(cancellationAcknowledgementTimeoutMilliseconds)
                {
                    polisher.cancel(operationIdentifier).operationIdentifier == operationIdentifier
                }
            }
            catch (_: TimeoutCancellationException)
            {
                false
            }
            catch (_: LocalInferenceException)
            {
                false
            }
        }
    }

    private fun successfulResult(
        exactRawTranscript: String,
        normalizedRawTranscript: String,
        cleanTranscript: String,
        polishedTranscript: String?,
        selectedTranscript: String,
        selectedMode: TranscriptMode
    ): ProcessedTranscript
    {
        return ProcessedTranscript(
            exactRawTranscript = exactRawTranscript,
            normalizedRawTranscript = normalizedRawTranscript,
            cleanTranscript = cleanTranscript,
            polishedTranscript = polishedTranscript,
            selectedTranscript = selectedTranscript,
            selectedMode = selectedMode,
            usedDeterministicFallback = false,
            fallbackReason = TranscriptFallbackReason.NONE
        )
    }

    private fun fallbackResult(
        exactRawTranscript: String,
        normalizedRawTranscript: String,
        cleanTranscript: String,
        fallbackReason: TranscriptFallbackReason
    ): ProcessedTranscript
    {
        return ProcessedTranscript(
            exactRawTranscript = exactRawTranscript,
            normalizedRawTranscript = normalizedRawTranscript,
            cleanTranscript = cleanTranscript,
            polishedTranscript = null,
            selectedTranscript = cleanTranscript,
            selectedMode = TranscriptMode.POLISHED,
            usedDeterministicFallback = true,
            fallbackReason = fallbackReason
        )
    }

    private fun normalizeRawWhitespace(exactRawTranscript: String): String
    {
        return TranscriptWhitespaceNormalizer.normalizePreservingLineBreaks(exactRawTranscript)
    }
}
