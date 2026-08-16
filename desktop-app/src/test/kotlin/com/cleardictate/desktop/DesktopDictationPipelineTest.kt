package com.cleardictate.desktop

import com.cleardictate.desktop.inference.CapturedAudio
import com.cleardictate.domain.TranscriptFallbackReason
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Proves desktop and remote audio share one stateful ASR-to-polish pipeline with terminal scrubbing.
 */
class DesktopDictationPipelineTest
{
    @Test
    fun desktopReleaseFeedsOneAsrSessionThenRewritesAndScrubsAudio() = runTest {
        val events = mutableListOf<String>()
        val capturedSamples = floatArrayOf(0.25F, -0.5F)
        val pipeline = createPipeline(events, capturedSamples)

        pipeline.prepareModels()
        pipeline.warmUpModels()
        pipeline.startRecording("headset-endpoint")
        val result = pipeline.finishDictation()

        assertEquals("um send the report tomorrow", result.rawTranscript)
        assertEquals("Send the report tomorrow.", result.polishedTranscript)
        assertEquals(37, result.timing.recognitionMilliseconds)
        assertEquals(
            listOf(
                "prepare-speech",
                "prepare-rewrite",
                "warm-speech",
                "warm-rewrite",
                "start:headset-endpoint",
                "stop",
                "open-asr",
                "accept:2",
                "finish-asr",
                "rewrite:um send the report tomorrow",
                "store:2:Send the report tomorrow."
            ),
            events
        )
        assertContentEquals(floatArrayOf(0.0F, 0.0F), capturedSamples)
    }

    @Test
    fun remoteAudioAdvancesAsrBeforeReleaseThenPolishesOnce() = runTest {
        val events = mutableListOf<String>()
        val acceptedAudio = mutableListOf<FloatArray>()
        var historySamples = floatArrayOf()
        val pipeline = DesktopDictationPipeline(
            audioRecorder = unusedAudioRecorder(),
            speechTranscriber = fakeTranscriber(events, acceptedAudio),
            transcriptRewriter = fakeRewriter(events),
            dictationHistory = DesktopDictationHistory { _, audio, _ -> historySamples = audio.samples.copyOf() }
        )
        val first = shortArrayOf(0, 16_384)
        val second = shortArrayOf(-32_768)

        val session = assertNotNull(pipeline.openRemoteDictation())
        session.acceptPcm16(first)
        assertEquals(listOf("open-asr", "accept:2"), events)
        session.acceptPcm16(second)
        val result = session.finish()

        assertEquals("Send the report tomorrow.", result.polishedTranscript)
        assertEquals(listOf("open-asr", "accept:2", "accept:1", "finish-asr", "rewrite:um send the report tomorrow"), events)
        assertContentEquals(floatArrayOf(0.0F, 0.5F), acceptedAudio[0])
        assertContentEquals(floatArrayOf(-1.0F), acceptedAudio[1])
        assertContentEquals(floatArrayOf(0.0F, 0.5F, -1.0F), historySamples)
        assertContentEquals(shortArrayOf(0, 0), first)
        assertContentEquals(shortArrayOf(0), second)
    }

    @Test
    fun cancelledRemoteStreamProducesNoRewriteOrHistoryAndReleasesAsr() = runTest {
        val events = mutableListOf<String>()
        var historyCount = 0
        val pipeline = DesktopDictationPipeline(
            audioRecorder = unusedAudioRecorder(),
            speechTranscriber = fakeTranscriber(events),
            transcriptRewriter = fakeRewriter(events),
            dictationHistory = DesktopDictationHistory { _, _, _ -> historyCount += 1 }
        )

        val first = assertNotNull(pipeline.openRemoteDictation())
        assertNull(pipeline.openRemoteDictation())
        first.acceptPcm16(shortArrayOf(1))
        first.cancel()
        val second = assertNotNull(pipeline.openRemoteDictation())
        second.cancel()

        assertEquals(listOf("open-asr", "accept:1", "cancel-asr", "open-asr", "cancel-asr"), events)
        assertEquals(0, historyCount)
    }

    @Test
    fun remoteBackgroundNoiseReturningKnownSilenceHallucinationProducesNoTextRewriteOrHistory() = runTest {
        val events = mutableListOf<String>()
        var historyCount = 0
        val pipeline = DesktopDictationPipeline(
            audioRecorder = unusedAudioRecorder(),
            speechTranscriber = fakeTranscriber(events, completedTranscript = "The."),
            transcriptRewriter = fakeRewriter(events),
            dictationHistory = DesktopDictationHistory { _, _, _ -> historyCount += 1 }
        )

        val session = assertNotNull(pipeline.openRemoteDictation())
        session.acceptPcm16(shortArrayOf(120, -470, 250))
        val result = session.finish()

        assertEquals("", result.rawTranscript)
        assertEquals("", result.polishedTranscript)
        assertEquals(listOf("open-asr", "accept:3", "finish-asr"), events)
        assertEquals(0, historyCount)
    }

    @Test
    fun remoteSpeechLevelAudioReturningTheIsPreserved() = runTest {
        val events = mutableListOf<String>()
        var historyCount = 0
        val pipeline = DesktopDictationPipeline(
            audioRecorder = unusedAudioRecorder(),
            speechTranscriber = fakeTranscriber(events, completedTranscript = "The."),
            transcriptRewriter = fakeRewriter(events),
            dictationHistory = DesktopDictationHistory { _, _, _ -> historyCount += 1 }
        )

        val session = assertNotNull(pipeline.openRemoteDictation())
        session.acceptPcm16(shortArrayOf(1_000))
        val result = session.finish()

        assertEquals("The.", result.rawTranscript)
        assertEquals(listOf("open-asr", "accept:1", "finish-asr", "rewrite:The."), events)
        assertEquals(1, historyCount)
    }

    private fun createPipeline(events: MutableList<String>, capturedSamples: FloatArray): DesktopDictationPipeline
    {
        return DesktopDictationPipeline(
            audioRecorder = object : DesktopAudioRecorder
            {
                override suspend fun startRecording(endpointIdentifier: String)
                {
                    events += "start:" + endpointIdentifier
                }

                override suspend fun stopRecording(): CapturedAudio
                {
                    events += "stop"
                    return CapturedAudio(16_000, capturedSamples)
                }

                override suspend fun cancelRecording()
                {
                    events += "cancel"
                }

                override fun close() = Unit
            },
            speechTranscriber = fakeTranscriber(events),
            transcriptRewriter = fakeRewriter(events),
            dictationHistory = DesktopDictationHistory { _, audio, result -> events += "store:" + audio.samples.size + ":" + result.polishedTranscript }
        )
    }

    private fun fakeTranscriber(events: MutableList<String>, acceptedAudio: MutableList<FloatArray> = mutableListOf(), completedTranscript: String = "um send the report tomorrow"): DesktopSpeechTranscriber
    {
        return object : DesktopSpeechTranscriber
        {
            override suspend fun prepare()
            {
                events += "prepare-speech"
            }

            override suspend fun warmUp()
            {
                events += "warm-speech"
            }

            override suspend fun openSession(): DesktopSpeechTranscriptionSession
            {
                events += "open-asr"
                return object : DesktopSpeechTranscriptionSession
                {
                    override suspend fun accept(capturedAudio: CapturedAudio)
                    {
                        events += "accept:" + capturedAudio.samples.size
                        acceptedAudio += capturedAudio.samples.copyOf()
                    }

                    override suspend fun finish(): DesktopSpeechRecognition
                    {
                        events += "finish-asr"
                        return DesktopSpeechRecognition(completedTranscript, 37)
                    }

                    override suspend fun cancel()
                    {
                        events += "cancel-asr"
                    }
                }
            }

            override fun close() = Unit
        }
    }

    private fun fakeRewriter(events: MutableList<String>): DesktopTranscriptRewriter
    {
        return object : DesktopTranscriptRewriter
        {
            override suspend fun prepare()
            {
                events += "prepare-rewrite"
            }

            override suspend fun warmUp()
            {
                events += "warm-rewrite"
            }

            override suspend fun rewrite(rawTranscript: String): DesktopTranscriptRewrite
            {
                events += "rewrite:" + rawTranscript
                return DesktopTranscriptRewrite("Send the report tomorrow.", DesktopPolishingOutcome(false, TranscriptFallbackReason.NONE))
            }

            override fun close() = Unit
        }
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
