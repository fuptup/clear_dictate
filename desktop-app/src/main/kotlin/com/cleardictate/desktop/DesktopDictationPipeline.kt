package com.cleardictate.desktop

import com.cleardictate.desktop.inference.CapturedAudio
import com.cleardictate.inference.remote.RemotePcmAudio
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Captures one push-to-talk utterance without performing recognition while the control is held.
 */
interface DesktopAudioRecorder : AutoCloseable
{
    suspend fun startRecording(endpointIdentifier: String)
    suspend fun stopRecording(): CapturedAudio
    suspend fun cancelRecording()
}

/**
 * Converts one completed in-memory recording into a faithful raw transcript.
 */
interface DesktopSpeechTranscriber : AutoCloseable
{
    suspend fun prepare()
    suspend fun warmUp() = Unit
    suspend fun transcribe(capturedAudio: CapturedAudio): String
}

/**
 * Rewrites a raw transcript into polished text without changing its meaning.
 */
interface DesktopTranscriptRewriter : AutoCloseable
{
    suspend fun prepare()
    suspend fun warmUp() = Unit
    suspend fun rewrite(rawTranscript: String): String
}

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
    val timing: DesktopDictationTiming
)

/**
 * Owns the release-triggered sequence: stop capture, transcribe, rewrite, then scrub audio.
 */
class DesktopDictationPipeline(
    private val audioRecorder: DesktopAudioRecorder,
    private val speechTranscriber: DesktopSpeechTranscriber,
    private val transcriptRewriter: DesktopTranscriptRewriter
) : AutoCloseable
{
    private val inferenceMutex = Mutex()

    /**
     * Loads and exercises both persistent GPU workers before dictation is enabled, moving one-time CUDA compilation and allocation outside the user's first release.
     */
    suspend fun prepareModels()
    {
        speechTranscriber.prepare()
        transcriptRewriter.prepare()
        speechTranscriber.warmUp()
        transcriptRewriter.warmUp()
    }

    suspend fun startRecording(endpointIdentifier: String)
    {
        audioRecorder.startRecording(endpointIdentifier)
    }

    suspend fun finishDictation(): DesktopDictationResult
    {
        return processCapturedAudio(audioRecorder.stopRecording())
    }

    /**
     * Converts one completed phone recording and runs it through the same serialized GPU pipeline as desktop capture.
     */
    suspend fun processRemoteDictation(remoteAudio: RemotePcmAudio): DesktopDictationResult
    {
        val normalizedSamples = FloatArray(remoteAudio.samples.size)
        try
        {
            for (sampleIndex in remoteAudio.samples.indices)
            {
                normalizedSamples[sampleIndex] = remoteAudio.samples[sampleIndex] / 32_768.0F
            }
        }
        finally
        {
            remoteAudio.samples.fill(0)
        }

        return processCapturedAudio(CapturedAudio(remoteAudio.sampleRateHertz, normalizedSamples))
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
     * Serializes access to both persistent model workers and erases normalized audio on every terminal path.
     */
    private suspend fun processCapturedAudio(capturedAudio: CapturedAudio): DesktopDictationResult
    {
        val requestStartedNanoseconds = System.nanoTime()
        return inferenceMutex.withLock {
            try
            {
                val processingStartedNanoseconds = System.nanoTime()
                val rawTranscript = speechTranscriber.transcribe(capturedAudio)
                val recognitionCompletedNanoseconds = System.nanoTime()
                val polishedTranscript = transcriptRewriter.rewrite(rawTranscript)
                val completedNanoseconds = System.nanoTime()
                DesktopDictationResult(
                    rawTranscript = rawTranscript,
                    polishedTranscript = polishedTranscript,
                    timing = DesktopDictationTiming(
                        queueMilliseconds = elapsedMilliseconds(requestStartedNanoseconds, processingStartedNanoseconds),
                        recognitionMilliseconds = elapsedMilliseconds(processingStartedNanoseconds, recognitionCompletedNanoseconds),
                        rewritingMilliseconds = elapsedMilliseconds(recognitionCompletedNanoseconds, completedNanoseconds),
                        totalMilliseconds = elapsedMilliseconds(requestStartedNanoseconds, completedNanoseconds)
                    )
                )
            }
            finally
            {
                capturedAudio.samples.fill(0.0F)
            }
        }
    }

    private fun elapsedMilliseconds(startNanoseconds: Long, endNanoseconds: Long): Long
    {
        return (endNanoseconds - startNanoseconds) / NANOSECONDS_PER_MILLISECOND
    }

    private companion object
    {
        const val NANOSECONDS_PER_MILLISECOND = 1_000_000L
    }
}
