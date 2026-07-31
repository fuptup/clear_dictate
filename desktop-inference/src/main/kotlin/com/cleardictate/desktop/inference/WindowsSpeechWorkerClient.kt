package com.cleardictate.desktop.inference

import com.cleardictate.domain.StreamingTranscriptAccumulator
import com.cleardictate.domain.StreamingTranscriptSnapshot
import com.cleardictate.inference.CancellationAcknowledgement
import com.cleardictate.inference.ClientSessionIdentifier
import com.cleardictate.inference.InferenceFailureCategory
import com.cleardictate.inference.InferenceOperationContext
import com.cleardictate.inference.LocalInferenceException
import com.cleardictate.inference.OperationIdentifier
import com.cleardictate.inference.OperationPrivacy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
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
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class WindowsSpeechWorkerConfiguration(
    val workerExecutable: Path,
    val modelDirectory: Path,
    val workerLauncherExecutable: Path = workerExecutable.resolveSibling("clear_dictate_worker_launcher.exe"),
    val startupTimeoutMilliseconds: Long = 5_000,
    val modelLoadTimeoutMilliseconds: Long = 90_000,
    val recordingStartTimeoutMilliseconds: Long = 10_000,
    val recordingStopTimeoutMilliseconds: Long = 30_000,
    val cancellationCompletionTimeoutMilliseconds: Long = 750
)
{
    init
    {
        require(startupTimeoutMilliseconds > 0) { "Worker startup timeout must be positive." }
        require(modelLoadTimeoutMilliseconds > 0) { "Model-load timeout must be positive." }
        require(recordingStartTimeoutMilliseconds > 0) { "Recording-start timeout must be positive." }
        require(recordingStopTimeoutMilliseconds > 0) { "Recording-stop timeout must be positive." }
        require(cancellationCompletionTimeoutMilliseconds > 0) { "Cancellation timeout must be positive." }
    }
}

/**
 * Stable public view of one active speech operation.
 *
 * The transcript stream retains only its latest immutable snapshot. Protocol
 * revisions are applied synchronously before the state is published.
 */
data class WindowsSpeechRecording(
    val operationIdentifier: OperationIdentifier,
    val transcript: StateFlow<StreamingTranscriptSnapshot>
)

/**
 * Owns one isolated native speech worker and at most one active recording.
 */
class WindowsSpeechWorkerClient private constructor(
    private val configuration: WindowsSpeechWorkerConfiguration,
    private val process: Process
) : AutoCloseable
{
    private data class ActiveWorkerRequestIdentity(
        val clientSessionIdentifier: ClientSessionIdentifier,
        val operationIdentifier: OperationIdentifier,
        val privacy: OperationPrivacy,
        val workerRequestToken: WorkerRequestToken
    )
    {
        fun matches(frame: WorkerProtocolMessage): Boolean
        {
            return clientSessionIdentifier == frame.clientSessionIdentifier &&
                operationIdentifier == frame.operationIdentifier &&
                privacy == frame.privacy &&
                workerRequestToken == frame.workerRequestToken
        }
    }

    private class ActiveRecording(
        val frameIdentity: ActiveWorkerRequestIdentity
    )
    {
        val submissionCompleted = CompletableFuture<Unit>()
        val recordingStarted = CompletableFuture<Unit>()
        val finalTranscript = CompletableFuture<String>()
        val cancellationCompleted = CompletableFuture<CancellationAcknowledgement>()
        val accumulator = StreamingTranscriptAccumulator()
        val accumulatorSessionIdentifier = accumulator.beginSession()
        val mutableTranscript = MutableStateFlow(StreamingTranscriptSnapshot.EMPTY)
        val publicRecording = WindowsSpeechRecording(
            operationIdentifier = frameIdentity.operationIdentifier,
            transcript = mutableTranscript.asStateFlow()
        )
    }

    private val protocolCodec = WorkerProtocolCodec()
    private val protocolState = WorkerProtocolStateMachine()
    private val requestTokenAllocator = WorkerRequestTokenAllocator()
    private val workerInput = DataInputStream(BufferedInputStream(process.inputStream))
    private val workerOutput = DataOutputStream(BufferedOutputStream(process.outputStream))

    /**
     * Serializes framing, state transitions, and operation ownership into one
     * process epoch shared by the command and reader threads.
     */
    private val protocolLock = Any()
    private val ready = CompletableFuture<Unit>()
    private val modelsLoaded = CompletableFuture<Unit>()
    private var startingRecording: ActiveRecording? = null
    private var activeRecording: ActiveRecording? = null

    @Volatile
    private var closed = false

    private val protocolReaderThread = Thread(::readWorkerFrames, "ClearDictate speech worker protocol reader").apply {
        isDaemon = true
        start()
    }

    private val diagnosticDrainThread = Thread(
        { process.errorStream.use { diagnosticStream -> diagnosticStream.copyTo(java.io.OutputStream.nullOutputStream()) } },
        "ClearDictate speech worker diagnostic drain"
    ).apply {
        isDaemon = true
        start()
    }

    suspend fun startRecording(
        operationContext: InferenceOperationContext,
        endpointIdentifier: String = ""
    ): WindowsSpeechRecording
    {
        ensureOpen()
        val frameIdentity = ActiveWorkerRequestIdentity(
            clientSessionIdentifier = operationContext.clientSessionIdentifier,
            operationIdentifier = operationContext.operationIdentifier,
            privacy = operationContext.privacy,
            workerRequestToken = requestTokenAllocator.allocate()
        )
        val recording = ActiveRecording(frameIdentity)

        synchronized(protocolLock)
        {
            ensureOpen()
            if (startingRecording != null || activeRecording != null)
            {
                throw LocalInferenceException(InferenceFailureCategory.REQUEST_REJECTED, "WORKER_BUSY")
            }
            startingRecording = recording
        }

        try
        {
            val startPayload = WorkerRecordingStartPayloadCodec.encode(
                WorkerRecordingStartConfiguration(endpointIdentifier)
            )
            withContext(Dispatchers.IO)
            {
                synchronized(protocolLock)
                {
                    ensureOpen()
                    if (startingRecording !== recording || activeRecording != null)
                    {
                        throw LocalInferenceException(InferenceFailureCategory.REQUEST_REJECTED, "WORKER_BUSY")
                    }

                    activeRecording = recording
                    startingRecording = null
                    writeFrameWhileLocked(
                        WorkerProtocolMessage(
                            type = WorkerMessageType.START_RECORDING,
                            clientSessionIdentifier = frameIdentity.clientSessionIdentifier,
                            operationIdentifier = frameIdentity.operationIdentifier,
                            privacy = frameIdentity.privacy,
                            workerRequestToken = frameIdentity.workerRequestToken,
                            payload = startPayload
                        )
                    )
                    recording.submissionCompleted.complete(Unit)
                }
            }

            awaitWithTimeout(
                recording.recordingStarted,
                configuration.recordingStartTimeoutMilliseconds,
                "RECORDING_START_TIMEOUT"
            )
            return recording.publicRecording
        }
        catch (exception: Exception)
        {
            recording.submissionCompleted.completeExceptionally(exception)
            if (exception is CancellationException)
            {
                close()
                throw exception
            }
            if (exception is WorkerRecordingStartPayloadException)
            {
                throw exception
            }

            val failure = processFailure(exception)
            if (speechOperationFailureRequiresWorkerClose(failure))
            {
                close()
            }
            throw failure
        }
    }

    suspend fun stopRecording(operationIdentifier: OperationIdentifier): String
    {
        return try
        {
            val recording = withContext(Dispatchers.IO)
            {
                synchronized(protocolLock)
                {
                    ensureOpen()
                    val currentRecording = requireActiveRecording(operationIdentifier)
                    writeFrameWhileLocked(createEmptyOperationFrame(WorkerMessageType.STOP_RECORDING, currentRecording.frameIdentity))
                    currentRecording
                }
            }

            awaitWithTimeout(
                recording.finalTranscript,
                configuration.recordingStopTimeoutMilliseconds,
                "RECORDING_STOP_TIMEOUT"
            )
        }
        catch (exception: Exception)
        {
            if (exception is CancellationException)
            {
                close()
                throw exception
            }

            val failure = processFailure(exception)
            if (speechOperationFailureRequiresWorkerClose(failure))
            {
                close()
            }
            throw failure
        }
    }

    suspend fun cancel(operationIdentifier: OperationIdentifier): CancellationAcknowledgement
    {
        val recordingBeingSubmitted = synchronized(protocolLock)
        {
            startingRecording?.takeIf { recording ->
                recording.frameIdentity.operationIdentifier == operationIdentifier
            }
        }
        recordingBeingSubmitted?.submissionCompleted?.await()

        val recording = try
        {
            withContext(Dispatchers.IO)
            {
                synchronized(protocolLock)
                {
                    ensureOpen()
                    val currentRecording = requireActiveRecording(operationIdentifier)
                    writeFrameWhileLocked(createEmptyOperationFrame(WorkerMessageType.CANCEL, currentRecording.frameIdentity))
                    currentRecording
                }
            }
        }
        catch (exception: Exception)
        {
            if (exception is LocalInferenceException && exception.diagnosticCode == "NO_ACTIVE_OPERATION")
            {
                throw exception
            }

            poisonClient(InferenceFailureCategory.PROCESS_DIED, "WORKER_WRITE_FAILED")
            throw processFailure(exception)
        }

        return try
        {
            awaitWithTimeout(
                recording.cancellationCompleted,
                configuration.cancellationCompletionTimeoutMilliseconds,
                "CANCELLATION_TIMEOUT"
            )
        }
        catch (exception: Exception)
        {
            if (exception is CancellationException)
            {
                close()
                throw exception
            }

            val cancellationFailure = processFailure(exception)
            if (cancellationFailureRequiresWorkerClose(cancellationFailure))
            {
                close()
            }
            throw cancellationFailure
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
                    if (protocolState.state == WorkerLifecycleState.IDLE && process.isAlive)
                    {
                        writeFrameWhileLocked(WorkerControlFrame(WorkerMessageType.SHUTDOWN, ByteArray(0)))
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

        if (!gracefulShutdownRequested ||
            !runCatching { process.waitFor(2, TimeUnit.SECONDS) }.getOrDefault(false))
        {
            terminateProcessTree()
        }

        runCatching { workerOutput.close() }
        runCatching { workerInput.close() }
        failAllPending(InferenceFailureCategory.PROCESS_DIED, "WORKER_CLOSED")
    }

    private suspend fun initialize()
    {
        try
        {
            writeFrame(WorkerControlFrame(WorkerMessageType.HELLO, ByteArray(0)))
            awaitWithTimeout(ready, configuration.startupTimeoutMilliseconds, "WORKER_START_TIMEOUT")

            val modelLoadPayload = WorkerSpeechModelLoadPayloadCodec.encode(
                WorkerSpeechModelLoadConfiguration(configuration.modelDirectory)
            )
            writeFrame(WorkerControlFrame(WorkerMessageType.LOAD_MODELS, modelLoadPayload))
            awaitWithTimeout(modelsLoaded, configuration.modelLoadTimeoutMilliseconds, "MODEL_LOAD_TIMEOUT")
        }
        catch (exception: Exception)
        {
            close()
            throw mapWorkerInitializationFailure(exception, ::processFailure)
        }
    }

    private fun readWorkerFrames()
    {
        try
        {
            while (!closed)
            {
                val frame = protocolCodec.read(workerInput)
                synchronized(protocolLock)
                {
                    val stateBeforeFrame = protocolState.state
                    try
                    {
                        protocolState.acceptWorkerFrame(frame)
                        routeWorkerFrame(frame, stateBeforeFrame)
                    }
                    finally
                    {
                        frame.payload.clearOwnedBytes()
                    }
                }
            }
        }
        catch (exception: Exception)
        {
            if (!closed)
            {
                poisonClient(
                    if (exception is WorkerProtocolException || exception is WorkerProtocolStateException)
                    {
                        InferenceFailureCategory.PROTOCOL_FAILURE
                    }
                    else
                    {
                        InferenceFailureCategory.PROCESS_DIED
                    },
                    "WORKER_READER_STOPPED"
                )
            }
        }
    }

    private fun routeWorkerFrame(frame: WorkerProtocolFrame, stateBeforeFrame: WorkerLifecycleState)
    {
        when (frame)
        {
            is WorkerControlFrame -> routeControlFrame(frame, stateBeforeFrame)
            is WorkerProtocolMessage -> routeOperationFrame(frame)
        }
    }

    private fun routeControlFrame(frame: WorkerControlFrame, stateBeforeFrame: WorkerLifecycleState)
    {
        when (frame.type)
        {
            WorkerMessageType.READY -> ready.complete(Unit)
            WorkerMessageType.MODELS_LOADED -> modelsLoaded.complete(Unit)
            WorkerMessageType.CONTROL_ERROR ->
            {
                val failure = if (stateBeforeFrame == WorkerLifecycleState.AWAITING_READY)
                {
                    LocalInferenceException(InferenceFailureCategory.PROTOCOL_FAILURE, "WORKER_START_REJECTED")
                }
                else
                {
                    val diagnosticCode = when (WorkerErrorPayloadCodec.decode(frame.payload))
                    {
                        1 -> "MODEL_LOAD_FAILED"
                        else -> "UNKNOWN_MODEL_LOAD_ERROR"
                    }
                    LocalInferenceException(InferenceFailureCategory.MODEL_VERIFICATION_FAILED, diagnosticCode)
                }
                ready.completeExceptionally(failure)
                modelsLoaded.completeExceptionally(failure)
            }

            else -> throw WorkerProtocolStateException(
                WorkerProtocolStateFailure.ILLEGAL_DIRECTION,
                protocolState.state
            )
        }
    }

    private fun routeOperationFrame(frame: WorkerProtocolMessage)
    {
        val recording = activeRecording
            ?: throw WorkerProtocolStateException(
                WorkerProtocolStateFailure.ILLEGAL_STATE,
                protocolState.state
            )
        if (!recording.frameIdentity.matches(frame))
        {
            throw WorkerProtocolStateException(
                WorkerProtocolStateFailure.OPERATION_IDENTITY_MISMATCH,
                protocolState.state
            )
        }

        when (frame.type)
        {
            WorkerMessageType.RECORDING_STARTED -> recording.recordingStarted.complete(Unit)
            WorkerMessageType.PARTIAL_TRANSCRIPT -> acceptPartialTranscript(recording, frame.payload)
            WorkerMessageType.CANCELLATION_ACKNOWLEDGED ->
            {
                // The public cancellation completes only after the recognition
                // thread has discarded its stream and publishes OPERATION_CANCELLED.
            }

            WorkerMessageType.FINAL_TRANSCRIPT ->
            {
                val finalTranscript = frame.payload.copyBytes().toString(Charsets.UTF_8)
                recording.mutableTranscript.value = StreamingTranscriptSnapshot(
                    completedRawTranscript = finalTranscript,
                    partialTranscript = ""
                )
                recording.finalTranscript.complete(finalTranscript)
                finishRecording(recording)
            }

            WorkerMessageType.OPERATION_CANCELLED ->
            {
                recording.accumulator.cancelSession(recording.accumulatorSessionIdentifier)
                recording.mutableTranscript.value = StreamingTranscriptSnapshot.EMPTY
                recording.recordingStarted.completeExceptionally(
                    LocalInferenceException(InferenceFailureCategory.CANCELLED)
                )
                recording.finalTranscript.completeExceptionally(
                    LocalInferenceException(InferenceFailureCategory.CANCELLED)
                )
                recording.cancellationCompleted.complete(
                    CancellationAcknowledgement(recording.frameIdentity.operationIdentifier)
                )
                finishRecording(recording)
            }

            WorkerMessageType.ERROR ->
            {
                val failure = mapSpeechOperationError(WorkerErrorPayloadCodec.decode(frame.payload))
                recording.recordingStarted.completeExceptionally(failure)
                recording.finalTranscript.completeExceptionally(failure)
                finishRecording(recording)
            }

            else -> throw WorkerProtocolStateException(
                WorkerProtocolStateFailure.ILLEGAL_DIRECTION,
                protocolState.state
            )
        }
    }

    private fun acceptPartialTranscript(recording: ActiveRecording, payload: WorkerPayload)
    {
        val payloadBytes = payload.copyBytes()
        val delta = try
        {
            WorkerTranscriptPayloadCodec.decode(payloadBytes)
        }
        finally
        {
            payloadBytes.fill(0)
        }
        if (delta.isComplete)
        {
            recording.accumulator.acceptCompleted(
                recording.accumulatorSessionIdentifier,
                delta.lineIdentifier,
                delta.text
            )
        }
        else
        {
            recording.accumulator.acceptPartial(
                recording.accumulatorSessionIdentifier,
                delta.lineIdentifier,
                delta.text
            )
        }
        recording.mutableTranscript.value = recording.accumulator.snapshot()
    }

    private fun finishRecording(recording: ActiveRecording)
    {
        recording.cancellationCompleted.completeExceptionally(
            LocalInferenceException(
                InferenceFailureCategory.CANCELLATION_NOT_ACKNOWLEDGED,
                "OPERATION_ALREADY_TERMINAL"
            )
        )
        if (activeRecording === recording)
        {
            activeRecording = null
        }
    }

    private suspend fun writeFrame(frame: WorkerProtocolFrame)
    {
        withContext(Dispatchers.IO)
        {
            synchronized(protocolLock)
            {
                writeFrameWhileLocked(frame)
            }
        }
    }

    private fun writeFrameWhileLocked(frame: WorkerProtocolFrame)
    {
        ensureOpen()
        try
        {
            protocolState.acceptHostFrame(frame)
            protocolCodec.write(frame, workerOutput)
        }
        finally
        {
            frame.payload.clearOwnedBytes()
        }
    }

    private fun createEmptyOperationFrame(
        type: WorkerMessageType,
        identity: ActiveWorkerRequestIdentity
    ): WorkerProtocolMessage
    {
        return WorkerProtocolMessage(
            type = type,
            clientSessionIdentifier = identity.clientSessionIdentifier,
            operationIdentifier = identity.operationIdentifier,
            privacy = identity.privacy,
            workerRequestToken = identity.workerRequestToken,
            payload = ByteArray(0)
        )
    }

    private fun requireActiveRecording(operationIdentifier: OperationIdentifier): ActiveRecording
    {
        return activeRecording?.takeIf { recording ->
            recording.frameIdentity.operationIdentifier == operationIdentifier
        } ?: throw LocalInferenceException(
            InferenceFailureCategory.CANCELLATION_NOT_ACKNOWLEDGED,
            "NO_ACTIVE_OPERATION"
        )
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
        runCatching {
            process.toHandle().descendants().forEach { descendant -> descendant.destroy() }
        }
        process.destroy()

        if (!runCatching { process.waitFor(2, TimeUnit.SECONDS) }.getOrDefault(false))
        {
            runCatching {
                process.toHandle().descendants().forEach { descendant -> descendant.destroyForcibly() }
            }
            process.destroyForcibly()
        }
    }

    private fun failAllPending(category: InferenceFailureCategory, diagnosticCode: String)
    {
        val failure = LocalInferenceException(category, diagnosticCode)
        ready.completeExceptionally(failure)
        modelsLoaded.completeExceptionally(failure)

        val recording = synchronized(protocolLock)
        {
            startingRecording?.submissionCompleted?.completeExceptionally(failure)
            startingRecording = null
            activeRecording.also { activeRecording = null }
        }
        recording?.recordingStarted?.completeExceptionally(failure)
        recording?.finalTranscript?.completeExceptionally(failure)
        recording?.cancellationCompleted?.completeExceptionally(failure)
    }

    private fun mapSpeechOperationError(errorCategory: Int): LocalInferenceException
    {
        return when (errorCategory)
        {
            101 -> LocalInferenceException(InferenceFailureCategory.REQUEST_REJECTED, "MICROPHONE_PRIVACY_BLOCKED")
            102 -> LocalInferenceException(InferenceFailureCategory.REQUEST_REJECTED, "NO_CAPTURE_DEVICE")
            103 -> LocalInferenceException(InferenceFailureCategory.REQUEST_REJECTED, "CAPTURE_DEVICE_UNAVAILABLE")
            104 -> LocalInferenceException(InferenceFailureCategory.REQUEST_REJECTED, "CAPTURE_DEVICE_BUSY")
            105 -> LocalInferenceException(InferenceFailureCategory.REQUEST_REJECTED, "CAPTURE_FORMAT_UNSUPPORTED")
            in 100..116 -> LocalInferenceException(InferenceFailureCategory.NATIVE_FAILURE, "CAPTURE_FAILED")
            5 -> LocalInferenceException(InferenceFailureCategory.NATIVE_FAILURE, "WORKER_NATIVE_FAILURE")
            else -> LocalInferenceException(InferenceFailureCategory.NATIVE_FAILURE, "UNKNOWN_WORKER_ERROR")
        }
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
        failAllPending(category, diagnosticCode)
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
            is WorkerProtocolException,
            is WorkerProtocolStateException,
            is WorkerTranscriptPayloadException -> LocalInferenceException(InferenceFailureCategory.PROTOCOL_FAILURE)
            is TimeoutException -> LocalInferenceException(InferenceFailureCategory.TIMEOUT)
            else -> LocalInferenceException(InferenceFailureCategory.PROCESS_DIED)
        }
    }

    private suspend fun <Result> CompletableFuture<Result>.await(): Result
    {
        return suspendCancellableCoroutine { continuation ->
            whenComplete { value, failure ->
                if (failure == null)
                {
                    continuation.resume(value)
                }
                else
                {
                    continuation.resumeWithException(failure.cause ?: failure)
                }
            }
        }
    }

    private suspend fun <Result> awaitWithTimeout(
        future: CompletableFuture<Result>,
        timeoutMilliseconds: Long,
        diagnosticCode: String
    ): Result
    {
        return try
        {
            withContext(Dispatchers.IO)
            {
                future.get(timeoutMilliseconds, TimeUnit.MILLISECONDS)
            }
        }
        catch (_: TimeoutException)
        {
            throw LocalInferenceException(InferenceFailureCategory.TIMEOUT, diagnosticCode)
        }
    }

    companion object
    {
        suspend fun start(configuration: WindowsSpeechWorkerConfiguration): WindowsSpeechWorkerClient
        {
            val workerExecutable = configuration.workerExecutable.toAbsolutePath().normalize()
            val workerLauncherExecutable = configuration.workerLauncherExecutable.toAbsolutePath().normalize()
            val modelDirectory = configuration.modelDirectory.toAbsolutePath().normalize()
            require(Files.isRegularFile(workerExecutable)) { "The ClearDictate speech worker executable does not exist." }
            require(Files.isRegularFile(workerLauncherExecutable)) { "The ClearDictate worker launcher executable does not exist." }
            require(Files.isDirectory(modelDirectory)) { "The ClearDictate speech model directory does not exist." }

            val normalizedConfiguration = configuration.copy(
                workerExecutable = workerExecutable,
                workerLauncherExecutable = workerLauncherExecutable,
                modelDirectory = modelDirectory
            )
            val process = CancellationSafeProcessStarter().start {
                val hostIdentity = WindowsCurrentProcessIdentity.capture()
                ProcessBuilder(
                    workerLauncherExecutable.toString(),
                    workerExecutable.toString(),
                    hostIdentity.processIdentifier.toString(),
                    hostIdentity.creationTimeTicks.toString()
                ).start()
            }
            val client = WindowsSpeechWorkerClient(normalizedConfiguration, process)

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

internal fun speechOperationFailureRequiresWorkerClose(failure: LocalInferenceException): Boolean
{
    return when (failure.category)
    {
        InferenceFailureCategory.TIMEOUT,
        InferenceFailureCategory.PROCESS_DIED,
        InferenceFailureCategory.PROTOCOL_FAILURE -> true

        InferenceFailureCategory.NATIVE_FAILURE -> failure.diagnosticCode != "CAPTURE_FAILED"
        else -> false
    }
}
