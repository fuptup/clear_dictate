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
 * Owns the persistent Qwen3-ASR process used after the user releases push-to-talk.
 */
class QwenDesktopSpeechTranscriber(private val runtimeConfiguration: DesktopRuntimeConfiguration?) : DesktopSpeechTranscriber
{
    private val ownershipLock = Any()

    @Volatile
    private var activeClient: QwenAsrWorkerClient? = null

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

    override suspend fun transcribe(capturedAudio: CapturedAudio): String
    {
        return try
        {
            acquireClient().transcribe(capturedAudio)
        }
        catch (throwable: Throwable)
        {
            synchronized(ownershipLock) { activeClient.also { activeClient = null } }?.close()
            throw throwable
        }
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

    private suspend fun acquireClient(): QwenAsrWorkerClient
    {
        synchronized(ownershipLock)
        {
            check(!closed) { "The Qwen speech transcriber is closed." }
            activeClient?.let { return it }
        }

        val configuration = requireNotNull(runtimeConfiguration) { "Qwen3-ASR is unavailable until the local runtime is installed." }
        val startedClient = QwenAsrWorkerClient.start(
            QwenAsrWorkerConfiguration(configuration.pythonExecutable, configuration.asrWorkerScript, configuration.asrModelDirectory, configuration.asrModelLock)
        )
        try
        {
            currentCoroutineContext().ensureActive()
            return synchronized(ownershipLock)
            {
                check(!closed) { "The Qwen speech transcriber is closed." }
                activeClient ?: startedClient.also { activeClient = it }
            }
        }
        catch (throwable: Throwable)
        {
            startedClient.close()
            throw throwable
        }
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
