package com.cleardictate.desktop

import com.cleardictate.desktop.inference.WindowsCaptureDevice
import com.cleardictate.desktop.inference.WindowsCaptureDeviceProvider
import com.cleardictate.desktop.inference.WindowsSpeechRecording
import com.cleardictate.desktop.inference.WindowsSpeechWorkerClient
import com.cleardictate.desktop.inference.WindowsSpeechWorkerConfiguration
import com.cleardictate.inference.CancellationAcknowledgement
import com.cleardictate.inference.ClientSessionIdentifier
import com.cleardictate.inference.InferenceOperationContext
import com.cleardictate.inference.OperationIdentifier
import com.cleardictate.inference.OperationPrivacy
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

interface DesktopSpeechWorker : AutoCloseable
{
    suspend fun startRecording(operationContext: InferenceOperationContext, endpointIdentifier: String): WindowsSpeechRecording
    suspend fun stopRecording(operationIdentifier: OperationIdentifier): String
    suspend fun cancel(operationIdentifier: OperationIdentifier): CancellationAcknowledgement
}

fun interface DesktopSpeechWorkerFactory
{
    suspend fun start(configuration: DesktopRuntimeConfiguration): DesktopSpeechWorker
}

/**
 * Owns one lazily loaded desktop speech worker and serializes recording commands.
 */
class DesktopSpeechRecorder(
    private val runtimeConfiguration: DesktopRuntimeConfiguration?,
    private val workerFactory: DesktopSpeechWorkerFactory = WindowsDesktopSpeechWorkerFactory()
) : AutoCloseable
{
    private val operationMutex = Mutex()
    private val ownershipLock = Any()
    private val operationSequence = AtomicLong(0)
    private val clientSessionIdentifier = ClientSessionIdentifier(
        "desktop_speech_" + UUID.randomUUID().toString().replace("-", "")
    )

    @Volatile
    private var activeWorker: DesktopSpeechWorker? = null

    @Volatile
    private var activeOperationIdentifier: OperationIdentifier? = null

    @Volatile
    private var closed = false

    suspend fun listActiveCaptureDevices(): List<WindowsCaptureDevice>
    {
        val configuration = runtimeConfiguration ?: return emptyList()
        return WindowsCaptureDeviceProvider(configuration.audioDeviceEnumeratorExecutable).listActiveCaptureDevices()
    }

    suspend fun startRecording(endpointIdentifier: String = ""): WindowsSpeechRecording
    {
        return operationMutex.withLock {
            ensureOpen()
            check(activeOperationIdentifier == null) { "A desktop recording is already active." }
            requireNotNull(runtimeConfiguration) { "Desktop speech recording is unavailable until the local worker and model are configured." }

            val operationIdentifier = OperationIdentifier("desktop_speech_${operationSequence.incrementAndGet()}")
            val operationContext = InferenceOperationContext(
                clientSessionIdentifier = clientSessionIdentifier,
                operationIdentifier = operationIdentifier,
                privacy = OperationPrivacy.PRIVATE
            )

            try
            {
                val recording = acquireWorker().startRecording(operationContext, endpointIdentifier)
                activeOperationIdentifier = operationIdentifier
                recording
            }
            catch (throwable: Throwable)
            {
                discardActiveWorker()
                throw throwable
            }
        }
    }

    suspend fun stopRecording(): String
    {
        return operationMutex.withLock {
            ensureOpen()
            val operationIdentifier = requireNotNull(activeOperationIdentifier) { "No desktop recording is active." }
            val worker = requireNotNull(activeWorker) { "The active desktop speech worker is unavailable." }

            try
            {
                worker.stopRecording(operationIdentifier).also {
                    activeOperationIdentifier = null
                }
            }
            catch (throwable: Throwable)
            {
                activeOperationIdentifier = null
                discardActiveWorker()
                throw throwable
            }
        }
    }

    suspend fun cancelRecording()
    {
        operationMutex.withLock {
            ensureOpen()
            val operationIdentifier = requireNotNull(activeOperationIdentifier) { "No desktop recording is active." }
            val worker = requireNotNull(activeWorker) { "The active desktop speech worker is unavailable." }

            try
            {
                worker.cancel(operationIdentifier)
                activeOperationIdentifier = null
            }
            catch (throwable: Throwable)
            {
                activeOperationIdentifier = null
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
            activeWorker.also { activeWorker = null }
        }
        workerToClose?.close()
    }

    private suspend fun acquireWorker(): DesktopSpeechWorker
    {
        synchronized(ownershipLock)
        {
            ensureOpen()
            activeWorker?.let { return it }
        }

        val startedWorker = workerFactory.start(requireNotNull(runtimeConfiguration))
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
        val workerToClose = synchronized(ownershipLock)
        {
            activeWorker.also { activeWorker = null }
        }
        workerToClose?.close()
    }

    private fun ensureOpen()
    {
        check(!closed) { "The desktop speech recorder is closed." }
    }
}

private class WindowsDesktopSpeechWorkerFactory : DesktopSpeechWorkerFactory
{
    override suspend fun start(configuration: DesktopRuntimeConfiguration): DesktopSpeechWorker
    {
        val client = WindowsSpeechWorkerClient.start(
            WindowsSpeechWorkerConfiguration(
                workerExecutable = configuration.speechWorkerExecutable,
                workerLauncherExecutable = configuration.workerLauncherExecutable,
                modelDirectory = configuration.speechModelDirectory
            )
        )
        return WindowsDesktopSpeechWorker(client)
    }
}

private class WindowsDesktopSpeechWorker(
    private val client: WindowsSpeechWorkerClient
) : DesktopSpeechWorker
{
    override suspend fun startRecording(operationContext: InferenceOperationContext, endpointIdentifier: String): WindowsSpeechRecording
    {
        return client.startRecording(operationContext, endpointIdentifier)
    }

    override suspend fun stopRecording(operationIdentifier: OperationIdentifier): String
    {
        return client.stopRecording(operationIdentifier)
    }

    override suspend fun cancel(operationIdentifier: OperationIdentifier): CancellationAcknowledgement
    {
        return client.cancel(operationIdentifier)
    }

    override fun close()
    {
        client.close()
    }
}
