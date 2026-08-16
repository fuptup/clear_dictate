package com.cleardictate.desktop

import com.cleardictate.desktop.inference.CapturedAudio
import com.cleardictate.domain.TranscriptFallbackReason
import com.cleardictate.inference.remote.RemoteDictationProtocol
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * Captures one desktop push-to-talk utterance before handing it to the shared streaming transcriber.
 */
interface DesktopAudioRecorder : AutoCloseable
{
    suspend fun startRecording(endpointIdentifier: String)
    suspend fun stopRecording(): CapturedAudio
    suspend fun cancelRecording()
}

/**
 * Creates exclusive stateful ASR sessions over one persistent model worker.
 */
interface DesktopSpeechTranscriber : AutoCloseable
{
    suspend fun prepare()
    suspend fun warmUp() = Unit
    suspend fun openSession(): DesktopSpeechTranscriptionSession
}

/**
 * Advances one ASR utterance incrementally and exposes a transcript only at its terminal boundary.
 */
interface DesktopSpeechTranscriptionSession
{
    suspend fun accept(capturedAudio: CapturedAudio)
    suspend fun finish(): DesktopSpeechRecognition
    suspend fun cancel()
}

/**
 * Carries the transcript together with cumulative worker-measured model compute time.
 */
data class DesktopSpeechRecognition(val transcript: String, val processingMilliseconds: Long)

/**
 * Rewrites a raw transcript into polished text without changing its meaning.
 */
interface DesktopTranscriptRewriter : AutoCloseable
{
    suspend fun prepare()
    suspend fun warmUp() = Unit
    suspend fun rewrite(rawTranscript: String): DesktopTranscriptRewrite
}

/**
 * Returns selected output together with whether it genuinely came from the language model.
 */
data class DesktopTranscriptRewrite(
    val selectedTranscript: String,
    val polishingOutcome: DesktopPolishingOutcome
)

/**
 * Retains the model/fallback decision without storing sensitive diagnostics.
 */
data class DesktopPolishingOutcome(
    val usedDeterministicFallback: Boolean,
    val fallbackReason: TranscriptFallbackReason
)

/**
 * Records only monotonic durations so latency can be diagnosed without retaining dictated text or audio.
 */
data class DesktopDictationTiming(
    val queueMilliseconds: Long,
    val recognitionMilliseconds: Long,
    val rewritingMilliseconds: Long,
    val totalMilliseconds: Long
)

data class DesktopDictationResult(
    val rawTranscript: String,
    val polishedTranscript: String,
    val timing: DesktopDictationTiming,
    val polishingOutcome: DesktopPolishingOutcome
)

/**
 * Persists each successful completed dictation locally for review and future model-training preparation.
 */
fun interface DesktopDictationHistory
{
    suspend fun record(recordedAt: Instant, capturedAudio: CapturedAudio, result: DesktopDictationResult)
}

/**
 * Accepts PCM fragments from one authenticated remote request and owns its terminal cleanup.
 */
interface DesktopRemoteDictationSession
{
    suspend fun acceptPcm16(samples: ShortArray)
    suspend fun finish(): DesktopDictationResult
    suspend fun cancel()
}

/**
 * Owns desktop release processing and phone streaming while serializing the two persistent GPU models.
 */
class DesktopDictationPipeline(
    private val audioRecorder: DesktopAudioRecorder,
    private val speechTranscriber: DesktopSpeechTranscriber,
    private val transcriptRewriter: DesktopTranscriptRewriter,
    private val dictationHistory: DesktopDictationHistory
) : AutoCloseable
{
    private val inferenceMutex = Mutex()

    /**
     * Loads both persistent GPU workers before the phone endpoint advertises readiness.
     */
    suspend fun prepareModels()
    {
        speechTranscriber.prepare()
        transcriptRewriter.prepare()
    }

    /**
     * Exercises both GPU paths behind the same operation mutex as real dictation.
     */
    suspend fun warmUpModels()
    {
        inferenceMutex.withLock {
            speechTranscriber.warmUp()
            transcriptRewriter.warmUp()
        }
    }

    suspend fun startRecording(endpointIdentifier: String)
    {
        audioRecorder.startRecording(endpointIdentifier)
    }

    suspend fun finishDictation(): DesktopDictationResult
    {
        return processCompletedDesktopAudio(audioRecorder.stopRecording())
    }

    /**
     * Reserves the single ASR stream at finger-down so incoming phone audio can be processed before release.
     */
    suspend fun openRemoteDictation(): DesktopRemoteDictationSession?
    {
        val lockOwner = Any()
        if (!inferenceMutex.tryLock(lockOwner))
        {
            return null
        }

        return try
        {
            StreamingRemoteDictationSession(lockOwner, speechTranscriber.openSession())
        }
        catch (throwable: Throwable)
        {
            inferenceMutex.unlock(lockOwner)
            throw throwable
        }
    }

    suspend fun cancelDictation()
    {
        audioRecorder.cancelRecording()
    }

    override fun close()
    {
        speechTranscriber.close()
        transcriptRewriter.close()
        audioRecorder.close()
    }

    /**
     * Feeds a completed desktop recording through the same stateful ASR API in one fragment.
     */
    private suspend fun processCompletedDesktopAudio(capturedAudio: CapturedAudio): DesktopDictationResult
    {
        val recordedAt = Instant.now()
        val requestStartedNanoseconds = System.nanoTime()
        return inferenceMutex.withLock {
            val session = speechTranscriber.openSession()
            var finished = false
            try
            {
                val processingStartedNanoseconds = System.nanoTime()
                session.accept(capturedAudio)
                val recognition = session.finish()
                finished = true
                val recognitionCompletedNanoseconds = System.nanoTime()
                if (isKnownSilenceHallucination(capturedAudio, recognition.transcript))
                {
                    return@withLock emptyDictationResult(
                        recognition = recognition,
                        queueMilliseconds = elapsedMilliseconds(requestStartedNanoseconds, processingStartedNanoseconds),
                        totalMilliseconds = elapsedMilliseconds(requestStartedNanoseconds, recognitionCompletedNanoseconds)
                    )
                }
                val rewrite = transcriptRewriter.rewrite(recognition.transcript)
                val completedNanoseconds = System.nanoTime()
                val result = DesktopDictationResult(
                    rawTranscript = recognition.transcript,
                    polishedTranscript = rewrite.selectedTranscript,
                    timing = DesktopDictationTiming(
                        queueMilliseconds = elapsedMilliseconds(requestStartedNanoseconds, processingStartedNanoseconds),
                        recognitionMilliseconds = recognition.processingMilliseconds,
                        rewritingMilliseconds = elapsedMilliseconds(recognitionCompletedNanoseconds, completedNanoseconds),
                        totalMilliseconds = elapsedMilliseconds(requestStartedNanoseconds, completedNanoseconds)
                    ),
                    polishingOutcome = rewrite.polishingOutcome
                )
                dictationHistory.record(recordedAt, capturedAudio, result)
                result
            }
            finally
            {
                if (!finished)
                {
                    runCatching { session.cancel() }
                }
                capturedAudio.samples.fill(0.0F)
            }
        }
    }

    /**
     * Retains normalized fragments only for the successful history record while ASR advances incrementally.
     */
    private inner class StreamingRemoteDictationSession(
        private val lockOwner: Any,
        private val transcriptionSession: DesktopSpeechTranscriptionSession
    ) : DesktopRemoteDictationSession
    {
        private val terminal = AtomicBoolean(false)
        private val capturedChunks = mutableListOf<FloatArray>()
        private val recordedAt = Instant.now()
        private var totalSampleCount = 0

        override suspend fun acceptPcm16(samples: ShortArray)
        {
            check(!terminal.get()) { "The remote dictation session is already finished." }
            require(samples.isNotEmpty()) { "Remote audio fragments cannot be empty." }
            require(totalSampleCount.toLong() + samples.size <= RemoteDictationProtocol.MAXIMUM_SAMPLE_COUNT) { "The remote recording is too long." }

            val normalizedSamples = FloatArray(samples.size)
            try
            {
                for (sampleIndex in samples.indices)
                {
                    normalizedSamples[sampleIndex] = samples[sampleIndex] / 32_768.0F
                }
                transcriptionSession.accept(CapturedAudio(RemoteDictationProtocol.SAMPLE_RATE_HERTZ, normalizedSamples))
                capturedChunks += normalizedSamples
                totalSampleCount += normalizedSamples.size
            }
            catch (throwable: Throwable)
            {
                normalizedSamples.fill(0.0F)
                abortAfterFailure()
                throw throwable
            }
            finally
            {
                samples.fill(0)
            }
        }

        override suspend fun finish(): DesktopDictationResult
        {
            check(totalSampleCount > 0) { "Remote dictation requires audio." }
            check(terminal.compareAndSet(false, true)) { "The remote dictation session is already finished." }
            var capturedAudio: CapturedAudio? = null
            var transcriptionFinished = false
            return try
            {
                val completeAudio = combineCapturedAudio()
                capturedAudio = completeAudio
                val recognition = transcriptionSession.finish()
                transcriptionFinished = true
                if (isKnownSilenceHallucination(completeAudio, recognition.transcript))
                {
                    return emptyDictationResult(recognition, queueMilliseconds = 0, totalMilliseconds = recognition.processingMilliseconds)
                }
                val rewritingStartedNanoseconds = System.nanoTime()
                val rewrite = transcriptRewriter.rewrite(recognition.transcript)
                val rewritingMilliseconds = elapsedMilliseconds(rewritingStartedNanoseconds, System.nanoTime())
                val result = DesktopDictationResult(
                    rawTranscript = recognition.transcript,
                    polishedTranscript = rewrite.selectedTranscript,
                    timing = DesktopDictationTiming(
                        queueMilliseconds = 0,
                        recognitionMilliseconds = recognition.processingMilliseconds,
                        rewritingMilliseconds = rewritingMilliseconds,
                        totalMilliseconds = recognition.processingMilliseconds + rewritingMilliseconds
                    ),
                    polishingOutcome = rewrite.polishingOutcome
                )
                dictationHistory.record(recordedAt, completeAudio, result)
                result
            }
            finally
            {
                if (!transcriptionFinished)
                {
                    runCatching { transcriptionSession.cancel() }
                }
                scrubChunks()
                capturedAudio?.samples?.fill(0.0F)
                inferenceMutex.unlock(lockOwner)
            }
        }

        override suspend fun cancel()
        {
            if (terminal.compareAndSet(false, true))
            {
                try
                {
                    transcriptionSession.cancel()
                }
                finally
                {
                    scrubChunks()
                    inferenceMutex.unlock(lockOwner)
                }
            }
        }

        private suspend fun abortAfterFailure()
        {
            if (terminal.compareAndSet(false, true))
            {
                try
                {
                    runCatching { transcriptionSession.cancel() }
                }
                finally
                {
                    scrubChunks()
                    inferenceMutex.unlock(lockOwner)
                }
            }
        }

        private fun combineCapturedAudio(): CapturedAudio
        {
            val combined = FloatArray(totalSampleCount)
            var destinationOffset = 0
            for (chunk in capturedChunks)
            {
                chunk.copyInto(combined, destinationOffset)
                destinationOffset += chunk.size
            }
            return CapturedAudio(RemoteDictationProtocol.SAMPLE_RATE_HERTZ, combined)
        }

        private fun scrubChunks()
        {
            capturedChunks.forEach { samples -> samples.fill(0.0F) }
            capturedChunks.clear()
            totalSampleCount = 0
        }
    }

    private fun elapsedMilliseconds(startNanoseconds: Long, endNanoseconds: Long): Long
    {
        return (endNanoseconds - startNanoseconds) / NANOSECONDS_PER_MILLISECOND
    }

    /**
     * Returns an empty successful result so clients can report no speech without treating a healthy model request as a failure.
     */
    private fun emptyDictationResult(recognition: DesktopSpeechRecognition, queueMilliseconds: Long, totalMilliseconds: Long): DesktopDictationResult
    {
        return DesktopDictationResult(
            rawTranscript = "",
            polishedTranscript = "",
            timing = DesktopDictationTiming(
                queueMilliseconds = queueMilliseconds,
                recognitionMilliseconds = recognition.processingMilliseconds,
                rewritingMilliseconds = 0,
                totalMilliseconds = totalMilliseconds
            ),
            polishingOutcome = DesktopPolishingOutcome(false, TranscriptFallbackReason.NONE)
        )
    }

    /**
     * Rejects Qwen's repeatedly observed low-level-noise hallucination while preserving the same word when the microphone contains a real speech-level signal.
     */
    private fun isKnownSilenceHallucination(capturedAudio: CapturedAudio, transcript: String): Boolean
    {
        val normalizedTranscript = transcript.trim().trimEnd('.', '!', '?').trim()
        return normalizedTranscript.equals(KNOWN_SILENCE_HALLUCINATION, ignoreCase = true) &&
            capturedAudio.samples.none { sample -> abs(sample) > MAXIMUM_OBSERVED_SILENCE_PEAK }
    }

    private companion object
    {
        const val NANOSECONDS_PER_MILLISECOND = 1_000_000L
        const val KNOWN_SILENCE_HALLUCINATION = "the"
        const val MAXIMUM_OBSERVED_SILENCE_PEAK = 0.015F
    }
}
