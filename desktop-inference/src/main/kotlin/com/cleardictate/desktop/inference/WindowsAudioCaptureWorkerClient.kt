package com.cleardictate.desktop.inference

import com.cleardictate.inference.CancellationAcknowledgement
import com.cleardictate.inference.InferenceFailureCategory
import com.cleardictate.inference.InferenceOperationContext
import com.cleardictate.inference.LocalInferenceException
import com.cleardictate.inference.OperationIdentifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

data class WindowsAudioCaptureWorkerConfiguration(
    val workerExecutable: Path,
    val workerLauncherExecutable: Path = workerExecutable.resolveSibling("clear_dictate_worker_launcher.exe"),
    val startupTimeoutMilliseconds: Long = 5_000,
    val recordingStartTimeoutMilliseconds: Long = 10_000,
    val recordingStopTimeoutMilliseconds: Long = 30_000,
    val cancellationCompletionTimeoutMilliseconds: Long = 750
)

/**
 * Supervises one isolated native microphone worker and owns the audio chunks for its active operation.
 */
class WindowsAudioCaptureWorkerClient private constructor(
    private val configuration: WindowsAudioCaptureWorkerConfiguration,
    private val process: Process
) : AutoCloseable
{
    private data class RequestIdentity(
        val operationContext: InferenceOperationContext,
        val workerRequestToken: WorkerRequestToken
    )
    {
        fun matches(frame: WorkerProtocolMessage): Boolean
        {
            return operationContext.clientSessionIdentifier == frame.clientSessionIdentifier &&
                operationContext.operationIdentifier == frame.operationIdentifier &&
                operationContext.privacy == frame.privacy &&
                workerRequestToken == frame.workerRequestToken
        }
    }

    private class ActiveRecording(val identity: RequestIdentity)
    {
        val recordingStarted = CompletableFuture<Unit>()
        val completedAudio = CompletableFuture<CapturedAudio>()
        val cancellationCompleted = CompletableFuture<CancellationAcknowledgement>()
        val audioChunks = mutableListOf<FloatArray>()
        var sampleRate: Int? = null

        fun append(capturedAudioChunk: CapturedAudio)
        {
            val establishedSampleRate = sampleRate
            if (establishedSampleRate != null && establishedSampleRate != capturedAudioChunk.sampleRate)
            {
                throw LocalInferenceException(InferenceFailureCategory.PROTOCOL_FAILURE, "CAPTURE_SAMPLE_RATE_CHANGED")
            }
            sampleRate = capturedAudioChunk.sampleRate
            audioChunks += capturedAudioChunk.samples
        }

        fun finish(): CapturedAudio
        {
            val totalSampleCount = audioChunks.sumOf(FloatArray::size)
            val combinedSamples = FloatArray(totalSampleCount)
            var destinationOffset = 0
            try
            {
                for (audioChunk in audioChunks)
                {
                    audioChunk.copyInto(combinedSamples, destinationOffset)
                    destinationOffset += audioChunk.size
                }
                return CapturedAudio(sampleRate = sampleRate ?: 16_000, samples = combinedSamples)
            }
            finally
            {
                scrubChunks()
            }
        }

        fun scrubChunks()
        {
            audioChunks.forEach { audioChunk -> audioChunk.fill(0.0F) }
            audioChunks.clear()
        }
    }

    private val protocolCodec = WorkerProtocolCodec()
    private val requestTokenAllocator = WorkerRequestTokenAllocator()
    private val workerInput = DataInputStream(BufferedInputStream(process.inputStream))
    private val workerOutput = DataOutputStream(BufferedOutputStream(process.outputStream))
    private val protocolLock = Any()
    private val ready = CompletableFuture<Unit>()
    private var activeRecording: ActiveRecording? = null

    @Volatile
    private var closed = false

    private val protocolReaderThread = Thread(::readWorkerFrames, "ClearDictate audio capture protocol reader").apply {
        isDaemon = true
        start()
    }

    private val diagnosticDrainThread = Thread(
        { process.errorStream.use { diagnosticStream -> diagnosticStream.copyTo(java.io.OutputStream.nullOutputStream()) } },
        "ClearDictate audio capture diagnostic drain"
    ).apply {
        isDaemon = true
        start()
    }

    suspend fun startRecording(operationContext: InferenceOperationContext, endpointIdentifier: String = "")
    {
        val recording = ActiveRecording(RequestIdentity(operationContext, requestTokenAllocator.allocate()))
        withContext(Dispatchers.IO)
        {
            synchronized(protocolLock)
            {
                ensureOpen()
                if (activeRecording != null)
                {
                    throw LocalInferenceException(InferenceFailureCategory.REQUEST_REJECTED, "CAPTURE_WORKER_BUSY")
                }
                activeRecording = recording
                writeOperationFrame(
                    WorkerMessageType.START_RECORDING,
                    recording,
                    WorkerRecordingStartPayloadCodec.encode(WorkerRecordingStartConfiguration(endpointIdentifier))
                )
            }
        }

        try
        {
            awaitWithTimeout(recording.recordingStarted, configuration.recordingStartTimeoutMilliseconds, "RECORDING_START_TIMEOUT")
        }
        catch (exception: Exception)
        {
            poisonClient(InferenceFailureCategory.PROCESS_DIED, "CAPTURE_START_FAILED")
            throw processFailure(exception)
        }
    }

    suspend fun stopRecording(operationIdentifier: OperationIdentifier): CapturedAudio
    {
        val recording = withContext(Dispatchers.IO)
        {
            synchronized(protocolLock)
            {
                val currentRecording = requireRecording(operationIdentifier)
                writeOperationFrame(WorkerMessageType.STOP_RECORDING, currentRecording, ByteArray(0))
                currentRecording
            }
        }

        return try
        {
            awaitWithTimeout(recording.completedAudio, configuration.recordingStopTimeoutMilliseconds, "RECORDING_STOP_TIMEOUT")
        }
        catch (exception: Exception)
        {
            poisonClient(InferenceFailureCategory.PROCESS_DIED, "CAPTURE_STOP_FAILED")
            throw processFailure(exception)
        }
    }

    suspend fun cancel(operationIdentifier: OperationIdentifier): CancellationAcknowledgement
    {
        val recording = withContext(Dispatchers.IO)
        {
            synchronized(protocolLock)
            {
                val currentRecording = requireRecording(operationIdentifier)
                writeOperationFrame(WorkerMessageType.CANCEL, currentRecording, ByteArray(0))
                currentRecording
            }
        }
        return try
        {
            awaitWithTimeout(recording.cancellationCompleted, configuration.cancellationCompletionTimeoutMilliseconds, "CAPTURE_CANCELLATION_TIMEOUT")
        }
        catch (exception: Exception)
        {
            poisonClient(InferenceFailureCategory.PROCESS_DIED, "CAPTURE_CANCELLATION_FAILED")
            throw processFailure(exception)
        }
    }

    @Synchronized
    override fun close()
    {
        if (closed)
        {
            return
        }

        val gracefulShutdownRequested = runCatching {
            synchronized(protocolLock)
            {
                try
                {
                    if (activeRecording == null && process.isAlive)
                    {
                        protocolCodec.write(WorkerControlFrame(WorkerMessageType.SHUTDOWN, ByteArray(0)), workerOutput)
                        true
                    }
                    else
                    {
                        false
                    }
                }
                finally
                {
                    closed = true
                }
            }
        }.getOrDefault(false)

        if (!gracefulShutdownRequested || !runCatching { process.waitFor(2, TimeUnit.SECONDS) }.getOrDefault(false))
        {
            terminateProcessTree()
        }
        runCatching { workerOutput.close() }
        runCatching { workerInput.close() }
        failPending(InferenceFailureCategory.PROCESS_DIED, "CAPTURE_WORKER_CLOSED")
    }

    private suspend fun initialize()
    {
        try
        {
            withContext(Dispatchers.IO)
            {
                synchronized(protocolLock)
                {
                    protocolCodec.write(WorkerControlFrame(WorkerMessageType.HELLO, ByteArray(0)), workerOutput)
                }
            }
            awaitWithTimeout(ready, configuration.startupTimeoutMilliseconds, "CAPTURE_WORKER_START_TIMEOUT")
        }
        catch (exception: Exception)
        {
            close()
            throw processFailure(exception)
        }
    }

    private fun readWorkerFrames()
    {
        try
        {
            while (!closed)
            {
                val frame = protocolCodec.read(workerInput)
                try
                {
                    synchronized(protocolLock)
                    {
                        routeWorkerFrame(frame)
                    }
                }
                finally
                {
                    frame.payload.clearOwnedBytes()
                }
            }
        }
        catch (exception: Exception)
        {
            if (!closed)
            {
                poisonClient(
                    if (exception is WorkerProtocolException) InferenceFailureCategory.PROTOCOL_FAILURE else InferenceFailureCategory.PROCESS_DIED,
                    "CAPTURE_WORKER_READER_STOPPED"
                )
            }
        }
    }

    private fun routeWorkerFrame(frame: WorkerProtocolFrame)
    {
        if (frame is WorkerControlFrame)
        {
            if (frame.type == WorkerMessageType.READY)
            {
                ready.complete(Unit)
                return
            }
            throw LocalInferenceException(InferenceFailureCategory.PROTOCOL_FAILURE, "UNEXPECTED_CAPTURE_CONTROL_FRAME")
        }

        frame as WorkerProtocolMessage
        val recording = activeRecording ?: throw LocalInferenceException(InferenceFailureCategory.PROTOCOL_FAILURE, "CAPTURE_EVENT_WITHOUT_OPERATION")
        if (!recording.identity.matches(frame))
        {
            throw LocalInferenceException(InferenceFailureCategory.PROTOCOL_FAILURE, "CAPTURE_OPERATION_IDENTITY_MISMATCH")
        }

        when (frame.type)
        {
            WorkerMessageType.RECORDING_STARTED -> recording.recordingStarted.complete(Unit)
            WorkerMessageType.AUDIO_CHUNK ->
            {
                val payloadBytes = frame.payload.copyBytes()
                try
                {
                    recording.append(CapturedAudioPayloadCodec.decode(payloadBytes))
                }
                finally
                {
                    payloadBytes.fill(0)
                }
            }
            WorkerMessageType.RECORDING_COMPLETE ->
            {
                recording.completedAudio.complete(recording.finish())
                finishRecording(recording)
            }
            WorkerMessageType.CANCELLATION_ACKNOWLEDGED -> Unit
            WorkerMessageType.OPERATION_CANCELLED ->
            {
                recording.scrubChunks()
                recording.cancellationCompleted.complete(CancellationAcknowledgement(recording.identity.operationContext.operationIdentifier))
                finishRecording(recording)
            }
            WorkerMessageType.ERROR ->
            {
                recording.scrubChunks()
                val failure = LocalInferenceException(InferenceFailureCategory.NATIVE_FAILURE, "CAPTURE_NATIVE_ERROR_${WorkerErrorPayloadCodec.decode(frame.payload)}")
                recording.recordingStarted.completeExceptionally(failure)
                recording.completedAudio.completeExceptionally(failure)
                recording.cancellationCompleted.completeExceptionally(failure)
                finishRecording(recording)
            }
            else -> throw LocalInferenceException(InferenceFailureCategory.PROTOCOL_FAILURE, "UNEXPECTED_CAPTURE_OPERATION_FRAME")
        }
    }

    private fun writeOperationFrame(type: WorkerMessageType, recording: ActiveRecording, payload: ByteArray)
    {
        val context = recording.identity.operationContext
        val frame = WorkerProtocolMessage(
            type = type,
            clientSessionIdentifier = context.clientSessionIdentifier,
            operationIdentifier = context.operationIdentifier,
            privacy = context.privacy,
            workerRequestToken = recording.identity.workerRequestToken,
            payload = payload
        )
        try
        {
            protocolCodec.write(frame, workerOutput)
        }
        finally
        {
            frame.payload.clearOwnedBytes()
        }
    }

    private fun requireRecording(operationIdentifier: OperationIdentifier): ActiveRecording
    {
        ensureOpen()
        return activeRecording?.takeIf { recording -> recording.identity.operationContext.operationIdentifier == operationIdentifier }
            ?: throw LocalInferenceException(InferenceFailureCategory.REQUEST_REJECTED, "NO_ACTIVE_CAPTURE")
    }

    private fun finishRecording(recording: ActiveRecording)
    {
        recording.cancellationCompleted.completeExceptionally(LocalInferenceException(InferenceFailureCategory.CANCELLATION_NOT_ACKNOWLEDGED, "CAPTURE_ALREADY_TERMINAL"))
        if (activeRecording === recording)
        {
            activeRecording = null
        }
    }

    private fun ensureOpen()
    {
        if (closed || !process.isAlive)
        {
            throw LocalInferenceException(InferenceFailureCategory.PROCESS_DIED)
        }
    }

    private fun terminateProcessTree()
    {
        runCatching { process.toHandle().descendants().forEach { descendant -> descendant.destroy() } }
        process.destroy()
        if (!runCatching { process.waitFor(2, TimeUnit.SECONDS) }.getOrDefault(false))
        {
            runCatching { process.toHandle().descendants().forEach { descendant -> descendant.destroyForcibly() } }
            process.destroyForcibly()
        }
    }

    private fun failPending(category: InferenceFailureCategory, diagnosticCode: String)
    {
        val failure = LocalInferenceException(category, diagnosticCode)
        ready.completeExceptionally(failure)
        val recording = synchronized(protocolLock) { activeRecording.also { activeRecording = null } }
        recording?.scrubChunks()
        recording?.recordingStarted?.completeExceptionally(failure)
        recording?.completedAudio?.completeExceptionally(failure)
        recording?.cancellationCompleted?.completeExceptionally(failure)
    }

    @Synchronized
    private fun poisonClient(category: InferenceFailureCategory, diagnosticCode: String)
    {
        if (closed)
        {
            return
        }
        closed = true
        runCatching { workerOutput.close() }
        runCatching { workerInput.close() }
        terminateProcessTree()
        failPending(category, diagnosticCode)
    }

    private fun processFailure(exception: Exception): LocalInferenceException
    {
        if (exception is ExecutionException && exception.cause is Exception)
        {
            return processFailure(exception.cause as Exception)
        }
        return when (exception)
        {
            is LocalInferenceException -> exception
            is TimeoutException -> LocalInferenceException(InferenceFailureCategory.TIMEOUT)
            else -> LocalInferenceException(InferenceFailureCategory.PROCESS_DIED)
        }
    }

    private suspend fun <Result> awaitWithTimeout(future: CompletableFuture<Result>, timeoutMilliseconds: Long, diagnosticCode: String): Result
    {
        return try
        {
            withContext(Dispatchers.IO) { future.get(timeoutMilliseconds, TimeUnit.MILLISECONDS) }
        }
        catch (_: TimeoutException)
        {
            throw LocalInferenceException(InferenceFailureCategory.TIMEOUT, diagnosticCode)
        }
        catch (cancellation: CancellationException)
        {
            throw cancellation
        }
    }

    companion object
    {
        suspend fun start(configuration: WindowsAudioCaptureWorkerConfiguration): WindowsAudioCaptureWorkerClient
        {
            val workerExecutable = configuration.workerExecutable.toAbsolutePath().normalize()
            val workerLauncherExecutable = configuration.workerLauncherExecutable.toAbsolutePath().normalize()
            require(Files.isRegularFile(workerExecutable)) { "The ClearDictate audio capture worker executable does not exist." }
            require(Files.isRegularFile(workerLauncherExecutable)) { "The ClearDictate worker launcher executable does not exist." }

            val process = CancellationSafeProcessStarter().start {
                val hostIdentity = WindowsCurrentProcessIdentity.capture()
                ProcessBuilder(
                    workerLauncherExecutable.toString(),
                    workerExecutable.toString(),
                    hostIdentity.processIdentifier.toString(),
                    hostIdentity.creationTimeTicks.toString()
                ).start()
            }
            val client = WindowsAudioCaptureWorkerClient(configuration.copy(workerExecutable = workerExecutable, workerLauncherExecutable = workerLauncherExecutable), process)
            try
            {
                currentCoroutineContext().ensureActive()
                client.initialize()
                return client
            }
            catch (throwable: Throwable)
            {
                client.close()
                throw throwable
            }
        }
    }
}
