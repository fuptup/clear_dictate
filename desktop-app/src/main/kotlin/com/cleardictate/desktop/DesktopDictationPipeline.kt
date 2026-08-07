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
    suspend fun transcribe(capturedAudio: CapturedAudio): String
}

/**
 * Rewrites a raw transcript into polished text without changing its meaning.
 */
interface DesktopTranscriptRewriter : AutoCloseable
{
    suspend fun prepare()
    suspend fun rewrite(rawTranscript: String): String
}

data class DesktopDictationResult(
    val rawTranscript: String,
    val polishedTranscript: String
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
     * Loads both persistent inference workers before dictation is enabled.
     */
    suspend fun prepareModels()
    {
        speechTranscriber.prepare()
        transcriptRewriter.prepare()
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
        return inferenceMutex.withLock {
            try
            {
                val rawTranscript = speechTranscriber.transcribe(capturedAudio)
                val polishedTranscript = transcriptRewriter.rewrite(rawTranscript)
                DesktopDictationResult(rawTranscript = rawTranscript, polishedTranscript = polishedTranscript)
            }
            finally
            {
                capturedAudio.samples.fill(0.0F)
            }
        }
    }
}
