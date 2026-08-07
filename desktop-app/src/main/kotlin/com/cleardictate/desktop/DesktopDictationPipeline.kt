package com.cleardictate.desktop

import com.cleardictate.desktop.inference.CapturedAudio

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
        val capturedAudio = audioRecorder.stopRecording()
        try
        {
            val rawTranscript = speechTranscriber.transcribe(capturedAudio)
            val polishedTranscript = transcriptRewriter.rewrite(rawTranscript)
            return DesktopDictationResult(rawTranscript = rawTranscript, polishedTranscript = polishedTranscript)
        }
        finally
        {
            capturedAudio.samples.fill(0.0F)
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
}
