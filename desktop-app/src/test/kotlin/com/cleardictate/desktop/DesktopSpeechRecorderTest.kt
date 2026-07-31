package com.cleardictate.desktop

import com.cleardictate.desktop.inference.WindowsSpeechRecording
import com.cleardictate.domain.StreamingTranscriptSnapshot
import com.cleardictate.inference.CancellationAcknowledgement
import com.cleardictate.inference.InferenceOperationContext
import com.cleardictate.inference.OperationIdentifier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopSpeechRecorderTest
{
    @Test
    fun `start and stop use one lazily started worker and clear the active operation`() = runTest {
        val workerFactory = RecordingSpeechWorkerFactory()
        val recorder = DesktopSpeechRecorder(readyConfiguration(), workerFactory)

        val firstRecording = recorder.startRecording()
        val firstFinalTranscript = recorder.stopRecording()
        val secondRecording = recorder.startRecording()

        assertEquals("recognized speech", firstFinalTranscript)
        assertTrue(firstRecording.operationIdentifier != secondRecording.operationIdentifier)
        assertEquals(1, workerFactory.createdWorkerCount)
        recorder.close()
        assertTrue(workerFactory.worker.closed)
    }

    @Test
    fun `cancel waits for worker acknowledgement before another recording can start`() = runTest {
        val workerFactory = RecordingSpeechWorkerFactory()
        val recorder = DesktopSpeechRecorder(readyConfiguration(), workerFactory)

        val cancelledRecording = recorder.startRecording()
        recorder.cancelRecording()
        val replacementRecording = recorder.startRecording()

        assertEquals(cancelledRecording.operationIdentifier, workerFactory.worker.cancelledOperationIdentifier)
        assertTrue(cancelledRecording.operationIdentifier != replacementRecording.operationIdentifier)
        recorder.close()
    }

    @Test
    fun `selected microphone endpoint is passed unchanged to the speech worker`() = runTest {
        val workerFactory = RecordingSpeechWorkerFactory()
        val recorder = DesktopSpeechRecorder(readyConfiguration(), workerFactory)

        recorder.startRecording("{0.0.1.00000000}.selected-endpoint")

        assertEquals("{0.0.1.00000000}.selected-endpoint", workerFactory.worker.startedEndpointIdentifier)
        recorder.cancelRecording()
        recorder.close()
    }

    private fun readyConfiguration(): DesktopRuntimeConfiguration
    {
        return DesktopRuntimeConfiguration(
            workerExecutable = Path.of("C:/ClearDictate/clear_dictate_worker.exe"),
            speechWorkerExecutable = Path.of("C:/ClearDictate/clear_dictate_speech_worker.exe"),
            audioDeviceEnumeratorExecutable = Path.of("C:/ClearDictate/clear_dictate_audio_device_enumerator.exe"),
            workerLauncherExecutable = Path.of("C:/ClearDictate/clear_dictate_worker_launcher.exe"),
            modelPath = Path.of("C:/ClearDictate/qwen.gguf"),
            speechModelDirectory = Path.of("C:/ClearDictate/moonshine")
        )
    }

    private class RecordingSpeechWorkerFactory : DesktopSpeechWorkerFactory
    {
        val worker = RecordingSpeechWorker()
        var createdWorkerCount = 0

        override suspend fun start(configuration: DesktopRuntimeConfiguration): DesktopSpeechWorker
        {
            createdWorkerCount += 1
            return worker
        }
    }

    private class RecordingSpeechWorker : DesktopSpeechWorker
    {
        var startedEndpointIdentifier: String? = null
        var cancelledOperationIdentifier: OperationIdentifier? = null
        var closed = false

        override suspend fun startRecording(operationContext: InferenceOperationContext, endpointIdentifier: String): WindowsSpeechRecording
        {
            startedEndpointIdentifier = endpointIdentifier
            return WindowsSpeechRecording(
                operationIdentifier = operationContext.operationIdentifier,
                transcript = MutableStateFlow(StreamingTranscriptSnapshot.EMPTY)
            )
        }

        override suspend fun stopRecording(operationIdentifier: OperationIdentifier): String
        {
            return "recognized speech"
        }

        override suspend fun cancel(operationIdentifier: OperationIdentifier): CancellationAcknowledgement
        {
            cancelledOperationIdentifier = operationIdentifier
            return CancellationAcknowledgement(operationIdentifier)
        }

        override fun close()
        {
            closed = true
        }
    }
}
