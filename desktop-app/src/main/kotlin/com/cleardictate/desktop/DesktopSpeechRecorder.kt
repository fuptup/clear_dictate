package com.cleardictate.desktop

import com.cleardictate.desktop.inference.CapturedAudio
import com.cleardictate.desktop.inference.QwenAsrWorkerClient
import com.cleardictate.desktop.inference.QwenAsrWorkerConfiguration
import com.cleardictate.desktop.inference.WindowsAudioCaptureWorkerClient
import com.cleardictate.desktop.inference.WindowsAudioCaptureWorkerConfiguration
import com.cleardictate.desktop.inference.WindowsCaptureDevice
import com.cleardictate.desktop.inference.WindowsCaptureDeviceProvider
import com.cleardictate.inference.ClientSessionIdentifier
import com.cleardictate.inference.InferenceOperationContext
import com.cleardictate.inference.OperationIdentifier
import com.cleardictate.inference.OperationPrivacy
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * Defines the native capture operations used by the desktop recorder.
 */
interface DesktopAudioCaptureWorker : AutoCloseable
{
    suspend fun startRecording(operationContext: InferenceOperationContext, endpointIdentifier: String)
    suspend fun stopRecording(operationIdentifier: OperationIdentifier): CapturedAudio
    suspend fun cancel(operationIdentifier: OperationIdentifier)
}

/**
 * Creates a native capture worker only when the first recording begins.
 */
fun interface DesktopAudioCaptureWorkerFactory
{
    suspend fun start(configuration: DesktopRuntimeConfiguration, inputLevelChanged: (Float) -> Unit): DesktopAudioCaptureWorker
}

/**
 * Defines the persistent recognition-client operations owned by the desktop speech transcriber.
 */
interface DesktopSpeechTranscriptionClient : AutoCloseable
{
    suspend fun warmUp()
    suspend fun openSession(): DesktopSpeechTranscriptionSession
}

/**
 * Starts the persistent recognition client from the verified desktop runtime configuration.
 */
fun interface DesktopSpeechTranscriptionClientFactory
{
    suspend fun start(configuration: DesktopRuntimeConfiguration): DesktopSpeechTranscriptionClient
}

/**
 * Owns one lazily started microphone worker and keeps raw audio in memory until release.
 */
class DesktopSpeechRecorder(
    private val runtimeConfiguration: DesktopRuntimeConfiguration?,
    private val workerFactory: DesktopAudioCaptureWorkerFactory = WindowsDesktopAudioCaptureWorkerFactory()
) : DesktopAudioRecorder
{
    private val operationMutex = Mutex()
    private val ownershipLock = Any()
    private val operationSequence = AtomicLong(0)
    private val clientSessionIdentifier = ClientSessionIdentifier("desktop_capture_" + UUID.randomUUID().toString().replace("-", ""))
    private val mutableMicrophoneActivity = MutableStateFlow(0.0F)

    val microphoneActivity: StateFlow<Float> = mutableMicrophoneActivity.asStateFlow()

    @Volatile
    private var activeWorker: DesktopAudioCaptureWorker? = null

    @Volatile
    private var activeOperationIdentifier: OperationIdentifier? = null

    @Volatile
    private var closed = false

    suspend fun listActiveCaptureDevices(): List<WindowsCaptureDevice>
    {
        val configuration = runtimeConfiguration ?: return emptyList()
        return WindowsCaptureDeviceProvider(configuration.audioDeviceEnumeratorExecutable).listActiveCaptureDevices()
    }

    override suspend fun startRecording(endpointIdentifier: String)
    {
        operationMutex.withLock {
            ensureOpen()
            check(activeOperationIdentifier == null) { "A desktop recording is already active." }
            requireNotNull(runtimeConfiguration) { "Desktop recording is unavailable until the local runtime is installed." }
            mutableMicrophoneActivity.value = 0.0F

            val operationIdentifier = OperationIdentifier("desktop_capture_${operationSequence.incrementAndGet()}")
            val operationContext = InferenceOperationContext(clientSessionIdentifier, operationIdentifier, OperationPrivacy.PRIVATE)
            try
            {
                acquireWorker().startRecording(operationContext, endpointIdentifier)
                activeOperationIdentifier = operationIdentifier
            }
            catch (throwable: Throwable)
            {
                discardActiveWorker()
                throw throwable
            }
        }
    }

    override suspend fun stopRecording(): CapturedAudio
    {
        return operationMutex.withLock {
            ensureOpen()
            val operationIdentifier = requireNotNull(activeOperationIdentifier) { "No desktop recording is active." }
            val worker = requireNotNull(activeWorker) { "The active desktop capture worker is unavailable." }
            try
            {
                worker.stopRecording(operationIdentifier).also {
                    activeOperationIdentifier = null
                    mutableMicrophoneActivity.value = 0.0F
                }
            }
            catch (throwable: Throwable)
            {
                activeOperationIdentifier = null
                mutableMicrophoneActivity.value = 0.0F
                discardActiveWorker()
                throw throwable
            }
        }
    }

    override suspend fun cancelRecording()
    {
        operationMutex.withLock {
            ensureOpen()
            val operationIdentifier = requireNotNull(activeOperationIdentifier) { "No desktop recording is active." }
            val worker = requireNotNull(activeWorker) { "The active desktop capture worker is unavailable." }
            try
            {
                worker.cancel(operationIdentifier)
                activeOperationIdentifier = null
                mutableMicrophoneActivity.value = 0.0F
            }
            catch (throwable: Throwable)
            {
                activeOperationIdentifier = null
                mutableMicrophoneActivity.value = 0.0F
                discardActiveWorker()
                throw throwable
            }
        }
    }

    override fun close()
    {
        val workerToClose = synchronized(ownershipLock)
        {
            if (closed)
            {
                return
            }
            closed = true
            activeOperationIdentifier = null
            mutableMicrophoneActivity.value = 0.0F
            activeWorker.also { activeWorker = null }
        }
        workerToClose?.close()
    }

    private suspend fun acquireWorker(): DesktopAudioCaptureWorker
    {
        synchronized(ownershipLock)
        {
            ensureOpen()
            activeWorker?.let { return it }
        }

        val startedWorker = workerFactory.start(requireNotNull(runtimeConfiguration)) { inputLevel -> mutableMicrophoneActivity.value = inputLevel }
        try
        {
            currentCoroutineContext().ensureActive()
            return synchronized(ownershipLock)
            {
                ensureOpen()
                activeWorker ?: startedWorker.also { activeWorker = it }
            }
        }
        catch (throwable: Throwable)
        {
            startedWorker.close()
            throw throwable
        }
    }

    private fun discardActiveWorker()
    {
        mutableMicrophoneActivity.value = 0.0F
        synchronized(ownershipLock) { activeWorker.also { activeWorker = null } }?.close()
    }

    private fun ensureOpen()
    {
        check(!closed) { "The desktop speech recorder is closed." }
    }
}

/**
 * Owns the persistent WSL Qwen3-ASR process and creates one stateful stream per utterance.
 */
class QwenDesktopSpeechTranscriber(
    private val runtimeConfiguration: DesktopRuntimeConfiguration?,
    private val clientFactory: DesktopSpeechTranscriptionClientFactory = QwenDesktopSpeechTranscriptionClientFactory()
) : DesktopSpeechTranscriber
{
    private val ownershipLock = Any()
    private val startupMutex = Mutex()

    @Volatile
    private var activeClient: DesktopSpeechTranscriptionClient? = null

    @Volatile
    private var closed = false

    /**
     * Starts and verifies the persistent ASR worker before recording is enabled.
     */
    override suspend fun prepare()
    {
        acquireClient()
    }

    /**
     * Exercises the CUDA recognition path during startup, so the first real utterance does not pay deferred kernel setup.
     */
    override suspend fun warmUp()
    {
        acquireClient().warmUp()
    }

    override suspend fun openSession(): DesktopSpeechTranscriptionSession
    {
        val transcriptionSession = try
        {
            acquireClient().openSession()
        }
        catch (throwable: Throwable)
        {
            discardActiveClient()
            throw throwable
        }

        return object : DesktopSpeechTranscriptionSession
        {
            override suspend fun accept(capturedAudio: CapturedAudio)
            {
                try
                {
                    transcriptionSession.accept(capturedAudio)
                }
                catch (throwable: Throwable)
                {
                    discardActiveClient()
                    throw throwable
                }
            }

            override suspend fun finish(): DesktopSpeechRecognition
            {
                return try
                {
                    transcriptionSession.finish()
                }
                catch (throwable: Throwable)
                {
                    discardActiveClient()
                    throw throwable
                }
            }

            override suspend fun cancel()
            {
                try
                {
                    transcriptionSession.cancel()
                }
                catch (throwable: Throwable)
                {
                    discardActiveClient()
                    throw throwable
                }
            }
        }
    }

    private fun discardActiveClient()
    {
        synchronized(ownershipLock) { activeClient.also { activeClient = null } }?.close()
    }

    override fun close()
    {
        synchronized(ownershipLock)
        {
            if (closed)
            {
                return
            }
            closed = true
            activeClient.also { activeClient = null }
        }?.close()
    }

    private suspend fun acquireClient(): DesktopSpeechTranscriptionClient
    {
        return startupMutex.withLock {
            synchronized(ownershipLock)
            {
                check(!closed) { "The Qwen speech transcriber is closed." }
                activeClient?.let { return@withLock it }
            }

            val configuration = requireNotNull(runtimeConfiguration) { "Qwen3-ASR is unavailable until the local runtime is installed." }
            val startedClient = clientFactory.start(configuration)
            try
            {
                currentCoroutineContext().ensureActive()
                synchronized(ownershipLock)
                {
                    check(!closed) { "The Qwen speech transcriber is closed." }
                    startedClient.also { activeClient = it }
                }
            }
            catch (throwable: Throwable)
            {
                startedClient.close()
                throw throwable
            }
        }
    }

}

/**
 * Adapts the Qwen worker protocol to the desktop transcriber's resource-ownership boundary.
 */
private class QwenDesktopSpeechTranscriptionClientFactory : DesktopSpeechTranscriptionClientFactory
{
    override suspend fun start(configuration: DesktopRuntimeConfiguration): DesktopSpeechTranscriptionClient
    {
        val client = QwenAsrWorkerClient.start(
            QwenAsrWorkerConfiguration(configuration.wslExecutable, configuration.wslDistribution, configuration.asrWorkerScript, configuration.asrModelLock)
        )
        return QwenDesktopSpeechTranscriptionClient(client)
    }
}

/**
 * Converts Qwen worker sessions into the desktop pipeline's recognition result without exposing worker protocol types.
 */
private class QwenDesktopSpeechTranscriptionClient(private val client: QwenAsrWorkerClient) : DesktopSpeechTranscriptionClient
{
    override suspend fun warmUp()
    {
        client.warmUp()
    }

    override suspend fun openSession(): DesktopSpeechTranscriptionSession
    {
        val workerSession = client.startSession()
        return object : DesktopSpeechTranscriptionSession
        {
            override suspend fun accept(capturedAudio: CapturedAudio)
            {
                workerSession.accept(capturedAudio)
            }

            override suspend fun finish(): DesktopSpeechRecognition
            {
                val recognition = workerSession.finish()
                return DesktopSpeechRecognition(recognition.transcript, recognition.processingMilliseconds)
            }

            override suspend fun cancel()
            {
                workerSession.cancel()
            }
        }
    }

    override fun close()
    {
        client.close()
    }
}

private class WindowsDesktopAudioCaptureWorkerFactory : DesktopAudioCaptureWorkerFactory
{
    override suspend fun start(configuration: DesktopRuntimeConfiguration, inputLevelChanged: (Float) -> Unit): DesktopAudioCaptureWorker
    {
        val client = WindowsAudioCaptureWorkerClient.start(
            WindowsAudioCaptureWorkerConfiguration(configuration.audioCaptureWorkerExecutable, configuration.workerLauncherExecutable),
            inputLevelChanged
        )
        return WindowsDesktopAudioCaptureWorker(client)
    }
}

private class WindowsDesktopAudioCaptureWorker(private val client: WindowsAudioCaptureWorkerClient) : DesktopAudioCaptureWorker
{
    override suspend fun startRecording(operationContext: InferenceOperationContext, endpointIdentifier: String)
    {
        client.startRecording(operationContext, endpointIdentifier)
    }

    override suspend fun stopRecording(operationIdentifier: OperationIdentifier): CapturedAudio = client.stopRecording(operationIdentifier)

    override suspend fun cancel(operationIdentifier: OperationIdentifier)
    {
        client.cancel(operationIdentifier)
    }

    override fun close()
    {
        client.close()
    }
}
