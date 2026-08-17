package com.cleardictate.desktop

import com.cleardictate.desktop.inference.CapturedAudio
import com.cleardictate.inference.InferenceOperationContext
import com.cleardictate.inference.OperationIdentifier
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Verifies that the desktop recorder owns capture operations without invoking recognition.
 */
class DesktopSpeechRecorderTest
{
    @Test
    fun `release returns captured audio and reuses one worker`() = runTest {
        val workerFactory = RecordingCaptureWorkerFactory()
        val recorder = DesktopSpeechRecorder(readyConfiguration(), workerFactory)

        recorder.startRecording("")
        val firstOperation = workerFactory.worker.startedOperationIdentifier
        val capturedAudio = recorder.stopRecording()
        recorder.startRecording("")
        val secondOperation = workerFactory.worker.startedOperationIdentifier

        assertContentEquals(floatArrayOf(0.25F, -0.5F), capturedAudio.samples)
        assertNotEquals(firstOperation, secondOperation)
        assertEquals(1, workerFactory.createdWorkerCount)
        recorder.cancelRecording()
        recorder.close()
        assertTrue(workerFactory.worker.closed)
    }

    @Test
    fun `selected microphone endpoint is passed unchanged`() = runTest {
        val workerFactory = RecordingCaptureWorkerFactory()
        val recorder = DesktopSpeechRecorder(readyConfiguration(), workerFactory)

        recorder.startRecording("{0.0.1.00000000}.selected-endpoint")

        assertEquals("{0.0.1.00000000}.selected-endpoint", workerFactory.worker.startedEndpointIdentifier)
        recorder.cancelRecording()
        recorder.close()
    }

    @Test
    fun `captured activity is exposed while recording and reset on release`() = runTest {
        val workerFactory = RecordingCaptureWorkerFactory()
        val recorder = DesktopSpeechRecorder(readyConfiguration(), workerFactory)

        recorder.startRecording("")
        workerFactory.publishInputLevel(0.65F)
        assertEquals(0.65F, recorder.microphoneActivity.value)

        recorder.stopRecording()
        assertEquals(0.0F, recorder.microphoneActivity.value)
        recorder.close()
    }

    @Test
    fun `concurrent preparation owns and closes exactly one recognition client`() = runTest {
        val clientFactory = BlockingSpeechClientFactory()
        val transcriber = QwenDesktopSpeechTranscriber(readyConfiguration(), clientFactory)

        val firstPreparation = async { transcriber.prepare() }
        clientFactory.firstStartEntered.await()
        val secondPreparation = async { transcriber.prepare() }
        yield()
        val createdClientCountWhileBlocked = clientFactory.clients.size
        clientFactory.allowStartToComplete.complete(Unit)
        firstPreparation.await()
        secondPreparation.await()
        transcriber.close()

        assertEquals(1, createdClientCountWhileBlocked)
        assertEquals(1, clientFactory.clients.size)
        assertTrue(clientFactory.clients.single().closed)
    }

    private fun readyConfiguration(): DesktopRuntimeConfiguration
    {
        return DesktopRuntimeConfiguration(
            textWorkerExecutable = Path.of("C:/ClearDictate/clear_dictate_worker.exe"),
            audioCaptureWorkerExecutable = Path.of("C:/ClearDictate/clear_dictate_audio_capture_worker.exe"),
            audioDeviceEnumeratorExecutable = Path.of("C:/ClearDictate/clear_dictate_audio_device_enumerator.exe"),
            workerLauncherExecutable = Path.of("C:/ClearDictate/clear_dictate_worker_launcher.exe"),
            wslExecutable = Path.of("C:/Windows/System32/wsl.exe"),
            wslDistribution = "Ubuntu",
            asrWorkerScript = Path.of("C:/ClearDictate/qwen_asr_worker.py"),
            asrModelLock = Path.of("C:/ClearDictate/qwen3-asr-lock.json"),
            textModelPath = Path.of("C:/ClearDictate/qwen3.5.gguf")
        )
    }

    private class RecordingCaptureWorkerFactory : DesktopAudioCaptureWorkerFactory
    {
        val worker = RecordingCaptureWorker()
        var createdWorkerCount = 0
        private var inputLevelChanged: (Float) -> Unit = {}

        override suspend fun start(configuration: DesktopRuntimeConfiguration, inputLevelChanged: (Float) -> Unit): DesktopAudioCaptureWorker
        {
            createdWorkerCount += 1
            this.inputLevelChanged = inputLevelChanged
            return worker
        }

        fun publishInputLevel(inputLevel: Float)
        {
            inputLevelChanged(inputLevel)
        }
    }

    private class RecordingCaptureWorker : DesktopAudioCaptureWorker
    {
        var startedEndpointIdentifier: String? = null
        var startedOperationIdentifier: OperationIdentifier? = null
        var closed = false

        override suspend fun startRecording(operationContext: InferenceOperationContext, endpointIdentifier: String)
        {
            startedEndpointIdentifier = endpointIdentifier
            startedOperationIdentifier = operationContext.operationIdentifier
        }

        override suspend fun stopRecording(operationIdentifier: OperationIdentifier): CapturedAudio
        {
            return CapturedAudio(16_000, floatArrayOf(0.25F, -0.5F))
        }

        override suspend fun cancel(operationIdentifier: OperationIdentifier)
        {
        }

        override fun close()
        {
            closed = true
        }
    }

    /**
     * Holds client startup open so two preparation calls deterministically exercise ownership publication.
     */
    private class BlockingSpeechClientFactory : DesktopSpeechTranscriptionClientFactory
    {
        val firstStartEntered = CompletableDeferred<Unit>()
        val allowStartToComplete = CompletableDeferred<Unit>()
        val clients = mutableListOf<RecordingSpeechClient>()

        override suspend fun start(configuration: DesktopRuntimeConfiguration): DesktopSpeechTranscriptionClient
        {
            val client = RecordingSpeechClient()
            clients += client
            firstStartEntered.complete(Unit)
            allowStartToComplete.await()
            return client
        }
    }

    /**
     * Records closure without implementing inference because the regression concerns startup ownership only.
     */
    private class RecordingSpeechClient : DesktopSpeechTranscriptionClient
    {
        var closed = false

        override suspend fun warmUp()
        {
        }

        override suspend fun openSession(): DesktopSpeechTranscriptionSession
        {
            error("Opening a recognition session is outside this ownership test.")
        }

        override fun close()
        {
            closed = true
        }
    }
}
