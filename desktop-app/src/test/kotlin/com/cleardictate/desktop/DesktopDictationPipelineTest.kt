package com.cleardictate.desktop

import com.cleardictate.desktop.inference.CapturedAudio
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * Proves that releasing push-to-talk runs recognition before rewriting and scrubs captured audio.
 */
class DesktopDictationPipelineTest
{
    @Test
    fun `release transcribes then rewrites and scrubs the captured samples`()
    {
        val events = mutableListOf<String>()
        val capturedSamples = floatArrayOf(0.25F, -0.5F)
        val pipeline = DesktopDictationPipeline(
            audioRecorder = object : DesktopAudioRecorder
            {
                override suspend fun startRecording(endpointIdentifier: String)
                {
                    events += "start:$endpointIdentifier"
                }

                override suspend fun stopRecording(): CapturedAudio
                {
                    events += "stop"
                    return CapturedAudio(sampleRate = 16_000, samples = capturedSamples)
                }

                override suspend fun cancelRecording()
                {
                    events += "cancel"
                }

                override fun close()
                {
                }
            },
            speechTranscriber = object : DesktopSpeechTranscriber
            {
                override suspend fun transcribe(capturedAudio: CapturedAudio): String
                {
                    events += "transcribe"
                    return "um send the report tomorrow"
                }

                override fun close()
                {
                }
            },
            transcriptRewriter = object : DesktopTranscriptRewriter
            {
                override suspend fun rewrite(rawTranscript: String): String
                {
                    events += "rewrite:$rawTranscript"
                    return "Send the report tomorrow."
                }

                override fun close()
                {
                }
            }
        )

        kotlinx.coroutines.test.runTest {
            pipeline.startRecording("headset-endpoint")
            val result = pipeline.finishDictation()

            assertEquals("um send the report tomorrow", result.rawTranscript)
            assertEquals("Send the report tomorrow.", result.polishedTranscript)
        }

        assertEquals(
            listOf(
                "start:headset-endpoint",
                "stop",
                "transcribe",
                "rewrite:um send the report tomorrow"
            ),
            events
        )
        assertContentEquals(floatArrayOf(0.0F, 0.0F), capturedSamples)
    }
}
