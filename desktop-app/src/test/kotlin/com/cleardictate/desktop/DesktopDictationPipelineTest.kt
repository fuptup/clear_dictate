package com.cleardictate.desktop

import com.cleardictate.desktop.inference.CapturedAudio
import com.cleardictate.inference.remote.RemotePcmAudio
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
                override suspend fun prepare()
                {
                    events += "prepare-speech"
                }

                override suspend fun warmUp()
                {
                    events += "warm-speech"
                }

                override suspend fun transcribe(capturedAudio: CapturedAudio): DesktopSpeechRecognition
                {
                    events += "transcribe"
                    return DesktopSpeechRecognition("um send the report tomorrow", 37)
                }

                override fun close()
                {
                }
            },
            transcriptRewriter = object : DesktopTranscriptRewriter
            {
                override suspend fun prepare()
                {
                    events += "prepare-rewrite"
                }

                override suspend fun warmUp()
                {
                    events += "warm-rewrite"
                }

                override suspend fun rewrite(rawTranscript: String): String
                {
                    events += "rewrite:$rawTranscript"
                    return "Send the report tomorrow."
                }

                override fun close()
                {
                }
            },
            dictationHistory = DesktopDictationHistory { _, capturedAudio, result ->
                events += "store:${capturedAudio.samples.size}:${result.polishedTranscript}"
            }
        )

        kotlinx.coroutines.test.runTest {
            pipeline.prepareModels()
            pipeline.warmUpModels()
            pipeline.startRecording("headset-endpoint")
            val result = pipeline.finishDictation()

            assertEquals("um send the report tomorrow", result.rawTranscript)
            assertEquals("Send the report tomorrow.", result.polishedTranscript)
            assertEquals(37, result.timing.recognitionMilliseconds)
        }

        assertEquals(
            listOf(
                "prepare-speech",
                "prepare-rewrite",
                "warm-speech",
                "warm-rewrite",
                "start:headset-endpoint",
                "stop",
                "transcribe",
                "rewrite:um send the report tomorrow",
                "store:2:Send the report tomorrow."
            ),
            events
        )
        assertContentEquals(floatArrayOf(0.0F, 0.0F), capturedSamples)
    }

    @Test
    fun `remote PCM16 audio uses the same pipeline and is scrubbed`()
    {
        val remoteSamples = shortArrayOf(0, 16_384, -32_768)
        var transcriberSamples = floatArrayOf()
        val pipeline = DesktopDictationPipeline(
            audioRecorder = unusedAudioRecorder(),
            speechTranscriber = object : DesktopSpeechTranscriber
            {
                override suspend fun prepare() = Unit

                override suspend fun transcribe(capturedAudio: CapturedAudio): DesktopSpeechRecognition
                {
                    transcriberSamples = capturedAudio.samples.copyOf()
                    return DesktopSpeechRecognition("raw", 19)
                }

                override fun close() = Unit
            },
            transcriptRewriter = object : DesktopTranscriptRewriter
            {
                override suspend fun prepare() = Unit
                override suspend fun rewrite(rawTranscript: String) = "polished"
                override fun close() = Unit
            },
            dictationHistory = DesktopDictationHistory { _, _, _ -> }
        )

        kotlinx.coroutines.test.runTest {
            val result = pipeline.processRemoteDictation(RemotePcmAudio(16_000, remoteSamples))
            assertEquals("polished", result.polishedTranscript)
        }

        assertContentEquals(floatArrayOf(0.0F, 0.5F, -1.0F), transcriberSamples)
        assertContentEquals(shortArrayOf(0, 0, 0), remoteSamples)
    }

    private fun unusedAudioRecorder(): DesktopAudioRecorder
    {
        return object : DesktopAudioRecorder
        {
            override suspend fun startRecording(endpointIdentifier: String) = error("Desktop recording was not expected.")
            override suspend fun stopRecording(): CapturedAudio = error("Desktop recording was not expected.")
            override suspend fun cancelRecording() = Unit
            override fun close() = Unit
        }
    }
}
