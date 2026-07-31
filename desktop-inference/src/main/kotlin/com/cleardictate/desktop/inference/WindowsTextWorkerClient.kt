package com.cleardictate.desktop.inference

import com.cleardictate.domain.TranscriptPolisher
import com.cleardictate.domain.TranscriptPolishingRequest
import com.cleardictate.inference.CancellationAcknowledgement
import com.cleardictate.inference.ClientSessionIdentifier
import com.cleardictate.inference.InferenceFailureCategory
import com.cleardictate.inference.InferenceOperationContext
import com.cleardictate.inference.LocalInferenceException
import com.cleardictate.inference.OperationIdentifier
import com.cleardictate.inference.OperationPrivacy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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

data class WindowsTextWorkerConfiguration(
    val workerExecutable: Path,
    val modelPath: Path,
    val workerLauncherExecutable: Path = workerExecutable.resolveSibling("clear_dictate_worker_launcher.exe"),
    val inferenceThreadCount: Int = 4,
    val startupTimeoutMilliseconds: Long = 5_000,
    val modelLoadTimeoutMilliseconds: Long = 90_000,
    val cancellationCompletionTimeoutMilliseconds: Long = 750
)
{
    init
    {
        require(inferenceThreadCount in 1..64) { "Inference thread count must be between 1 and 64." }
        require(startupTimeoutMilliseconds > 0) { "Worker startup timeout must be positive." }
        require(modelLoadTimeoutMilliseconds > 0) { "Model-load timeout must be positive." }
        require(cancellationCompletionTimeoutMilliseconds > 0) { "Cancellation timeout must be positive." }
    }
}

/**
 * Owns one isolated native worker process and exposes it through the shared
 * transcript-polishing domain interface.
 *
 * The process command line contains only executable and host-lifetime identity values.
 * The model path and all transcript text travel through private anonymous pipes using
 * the bounded protocol.
 */
class WindowsTextWorkerClient private constructor(
    private val configuration: WindowsTextWorkerConfiguration,
    private val process: Process
) : TranscriptPolisher, AutoCloseable
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

    private data class ActivePolishOperation(
        val frameIdentity: ActiveWorkerRequestIdentity,
        val submissionCompleted: CompletableFuture<Unit>,
        val result: CompletableFuture<String>,
        val cancellationCompleted: CompletableFuture<CancellationAcknowledgement>
    )

    private val protocolCodec = WorkerProtocolCodec()
    private val protocolState = WorkerProtocolStateMachine()
    private val requestTokenAllocator = WorkerRequestTokenAllocator()
    private val workerInput = DataInputStream(BufferedInputStream(process.inputStream))
    private val workerOutput = DataOutputStream(BufferedOutputStream(process.outputStream))
    /**
     * Serializes every protocol transition, pipe write, inbound transition, and
     * active-operation update into one process epoch.
     */
    private val protocolLock = Any()
    private val ready = CompletableFuture<Unit>()
    private val modelsLoaded = CompletableFuture<Unit>()
    private var startingOperation: ActivePolishOperation? = null
    private var activeOperation: ActivePolishOperation? = null
    @Volatile
    private var closed = false

    private val protocolReaderThread = Thread(::readWorkerFrames, "ClearDictate worker protocol reader").apply {
        isDaemon = true
        start()
    }

    private val diagnosticDrainThread = Thread(
        { process.errorStream.use { diagnosticStream -> diagnosticStream.copyTo(java.io.OutputStream.nullOutputStream()) } },
        "ClearDictate worker diagnostic drain"
    ).apply {
        isDaemon = true
        start()
    }

    override suspend fun polish(operationContext: InferenceOperationContext, request: TranscriptPolishingRequest): String
    {
        ensureOpen()
        if (request.untrustedCleanTranscript.isEmpty())
        {
            throw LocalInferenceException(InferenceFailureCategory.REQUEST_REJECTED, "EMPTY_TRANSCRIPT")
        }

        val workerRequestToken = requestTokenAllocator.allocate()
        val frameIdentity = ActiveWorkerRequestIdentity(
            clientSessionIdentifier = operationContext.clientSessionIdentifier,
            operationIdentifier = operationContext.operationIdentifier,
            privacy = operationContext.privacy,
            workerRequestToken = workerRequestToken
        )
        val operation = ActivePolishOperation(
            frameIdentity = frameIdentity,
            submissionCompleted = CompletableFuture(),
            result = CompletableFuture(),
            cancellationCompleted = CompletableFuture()
        )

        synchronized(protocolLock)
        {
            ensureOpen()
            if (startingOperation != null || activeOperation != null)
            {
                throw LocalInferenceException(InferenceFailureCategory.REQUEST_REJECTED, "WORKER_BUSY")
            }
            startingOperation = operation
        }

        try
        {
            withContext(Dispatchers.IO)
            {
                synchronized(protocolLock)
                {
                    ensureOpen()
                    if (startingOperation !== operation || activeOperation != null)
                    {
                        throw LocalInferenceException(InferenceFailureCategory.REQUEST_REJECTED, "WORKER_BUSY")
                    }
                    activeOperation = operation
                    startingOperation = null

                    writeFrameWhileLocked(createPolishFrame(frameIdentity, request.untrustedCleanTranscript))
                    operation.submissionCompleted.complete(Unit)
                }
            }
        }
        catch (exception: Exception)
        {
            operation.submissionCompleted.completeExceptionally(exception)
            poisonClient(InferenceFailureCategory.PROCESS_DIED, "WORKER_WRITE_FAILED")
            throw processFailure(exception)
        }

        return operation.result.await()
    }

    override suspend fun cancel(operationIdentifier: OperationIdentifier): CancellationAcknowledgement
    {
        ensureOpen()
        val operationBeingSubmitted = synchronized(protocolLock)
        {
            startingOperation?.takeIf { operation ->
                operation.frameIdentity.operationIdentifier == operationIdentifier
            }
        }
        if (operationBeingSubmitted != null)
        {
            operationBeingSubmitted.submissionCompleted.await()
        }

        val operation = try
        {
            withContext(Dispatchers.IO)
            {
                synchronized(protocolLock)
                {
                    ensureOpen()
                    val currentOperation = activeOperation?.takeIf { active ->
                        active.frameIdentity.operationIdentifier == operationIdentifier
                    } ?: throw LocalInferenceException(
                        InferenceFailureCategory.CANCELLATION_NOT_ACKNOWLEDGED,
                        "NO_ACTIVE_OPERATION"
                    )

                    writeFrameWhileLocked(
                        WorkerProtocolMessage(
                            type = WorkerMessageType.CANCEL,
                            clientSessionIdentifier = currentOperation.frameIdentity.clientSessionIdentifier,
                            operationIdentifier = currentOperation.frameIdentity.operationIdentifier,
                            privacy = currentOperation.frameIdentity.privacy,
                            workerRequestToken = currentOperation.frameIdentity.workerRequestToken,
                            payload = ByteArray(0)
                        )
                    )
                    currentOperation
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
                operation.cancellationCompleted,
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

            val modelLoadPayload = WorkerModelLoadPayloadCodec.encode(
                WorkerModelLoadConfiguration(
                    modelPath = configuration.modelPath,
                    inferenceThreadCount = configuration.inferenceThreadCount
                )
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
            is WorkerControlFrame ->
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
                            val modelLoadCategory = when (WorkerErrorPayloadCodec.decode(frame.payload))
                            {
                                1 -> "MODEL_LOAD_FAILED"
                                else -> "UNKNOWN_MODEL_LOAD_ERROR"
                            }
                            LocalInferenceException(
                                InferenceFailureCategory.MODEL_VERIFICATION_FAILED,
                                modelLoadCategory
                            )
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

            is WorkerProtocolMessage -> routeOperationFrame(frame)
        }
    }

    private fun routeOperationFrame(frame: WorkerProtocolMessage)
    {
        val operation = activeOperation
            ?: throw WorkerProtocolStateException(
                WorkerProtocolStateFailure.ILLEGAL_STATE,
                protocolState.state
            )

        if (!operation.frameIdentity.matches(frame))
        {
            throw WorkerProtocolStateException(
                WorkerProtocolStateFailure.OPERATION_IDENTITY_MISMATCH,
                protocolState.state
            )
        }

        when (frame.type)
        {
            WorkerMessageType.CANCELLATION_ACKNOWLEDGED ->
            {
                // This frame means cancellation was accepted, not that native work
                // has stopped. The public cancellation contract completes only on
                // OPERATION_CANCELLED below.
            }

            WorkerMessageType.POLISHED_TRANSCRIPT ->
            {
                operation.result.complete(frame.payload.copyBytes().toString(Charsets.UTF_8))
                finishOperation(operation)
            }

            WorkerMessageType.OPERATION_CANCELLED ->
            {
                operation.cancellationCompleted.complete(
                    CancellationAcknowledgement(operation.frameIdentity.operationIdentifier)
                )
                operation.result.completeExceptionally(
                    LocalInferenceException(InferenceFailureCategory.CANCELLED)
                )
                finishOperation(operation)
            }

            WorkerMessageType.ERROR ->
            {
                val errorCategory = WorkerErrorPayloadCodec.decode(frame.payload)
                operation.result.completeExceptionally(mapOperationError(errorCategory))
                finishOperation(operation)
            }

            else -> throw WorkerProtocolStateException(
                WorkerProtocolStateFailure.ILLEGAL_DIRECTION,
                protocolState.state
            )
        }
    }

    private fun finishOperation(operation: ActivePolishOperation)
    {
        operation.cancellationCompleted.completeExceptionally(
            LocalInferenceException(
                InferenceFailureCategory.CANCELLATION_NOT_ACKNOWLEDGED,
                "OPERATION_ALREADY_TERMINAL"
            )
        )

        if (activeOperation === operation)
        {
            activeOperation = null
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

    private fun createPolishFrame(identity: ActiveWorkerRequestIdentity, cleanTranscript: String): WorkerProtocolMessage
    {
        val transcriptBytes = cleanTranscript.toByteArray(Charsets.UTF_8)
        return try
        {
            WorkerProtocolMessage(
                type = WorkerMessageType.POLISH_TRANSCRIPT,
                clientSessionIdentifier = identity.clientSessionIdentifier,
                operationIdentifier = identity.operationIdentifier,
                privacy = identity.privacy,
                workerRequestToken = identity.workerRequestToken,
                payload = transcriptBytes
            )
        }
        finally
        {
            transcriptBytes.fill(0)
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

        val operation = synchronized(protocolLock)
        {
            startingOperation?.submissionCompleted?.completeExceptionally(failure)
            startingOperation = null
            activeOperation.also { activeOperation = null }
        }
        operation?.result?.completeExceptionally(failure)
        operation?.cancellationCompleted?.completeExceptionally(failure)
    }

    private fun mapOperationError(errorCategory: Int): LocalInferenceException
    {
        return when (errorCategory)
        {
            1 -> LocalInferenceException(InferenceFailureCategory.CONTEXT_LIMIT_EXCEEDED)
            2 -> LocalInferenceException(InferenceFailureCategory.REQUEST_REJECTED, "OUTPUT_LIMIT_REACHED")
            3 -> LocalInferenceException(InferenceFailureCategory.REQUEST_REJECTED, "WORKER_BUSY")
            4 -> LocalInferenceException(InferenceFailureCategory.PROCESS_DIED, "WORKER_CLOSING")
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
            is WorkerProtocolStateException -> LocalInferenceException(InferenceFailureCategory.PROTOCOL_FAILURE)
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

    private suspend fun <Result> awaitWithTimeout(future: CompletableFuture<Result>, timeoutMilliseconds: Long, diagnosticCode: String): Result
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
        suspend fun start(configuration: WindowsTextWorkerConfiguration): WindowsTextWorkerClient
        {
            val workerExecutable = configuration.workerExecutable.toAbsolutePath().normalize()
            val workerLauncherExecutable = configuration.workerLauncherExecutable.toAbsolutePath().normalize()
            val modelPath = configuration.modelPath.toAbsolutePath().normalize()
            require(Files.isRegularFile(workerExecutable)) { "The ClearDictate worker executable does not exist." }
            require(Files.isRegularFile(workerLauncherExecutable)) { "The ClearDictate worker launcher executable does not exist." }
            require(Files.isRegularFile(modelPath)) { "The ClearDictate text model does not exist." }

            val normalizedConfiguration = configuration.copy(
                workerExecutable = workerExecutable,
                workerLauncherExecutable = workerLauncherExecutable,
                modelPath = modelPath
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
            val client = WindowsTextWorkerClient(
                normalizedConfiguration,
                process
            )

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

internal fun cancellationFailureRequiresWorkerClose(failure: LocalInferenceException): Boolean
{
    return failure.category != InferenceFailureCategory.CANCELLATION_NOT_ACKNOWLEDGED ||
        failure.diagnosticCode != "OPERATION_ALREADY_TERMINAL"
}

internal fun mapWorkerInitializationFailure(
    exception: Exception,
    safeFailureMapper: (Exception) -> LocalInferenceException
): Exception
{
    return if (exception is CancellationException)
    {
        exception
    }
    else
    {
        safeFailureMapper(exception)
    }
}
