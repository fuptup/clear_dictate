package com.cleardictate.desktop.inference

import com.cleardictate.inference.InferenceFailureCategory
import com.cleardictate.inference.LocalInferenceException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

data class QwenAsrWorkerConfiguration(
    val wslExecutable: Path,
    val wslDistribution: String,
    val workerScript: Path,
    val modelLock: Path,
    val startupTimeoutMilliseconds: Long = 240_000,
    val transcriptionTimeoutMilliseconds: Long = 120_000
)

/**
 * Separates cumulative model compute from transport and release latency.
 */
data class QwenAsrTranscription(val transcript: String, val processingMilliseconds: Long)

/**
 * Owns exclusive access to one stateful utterance in the persistent Qwen worker.
 */
class QwenAsrWorkerSession internal constructor(private val client: QwenAsrWorkerClient, private val lockOwner: Any)
{
    private val terminal = AtomicBoolean(false)

    /**
     * Advances recognition with one mono 16 kHz fragment before the user releases push-to-talk.
     */
    suspend fun accept(capturedAudio: CapturedAudio)
    {
        check(!terminal.get()) { "The Qwen3-ASR session is already finished." }
        if (capturedAudio.samples.isNotEmpty())
        {
            client.acceptAudio(lockOwner, capturedAudio)
        }
    }

    /**
     * Flushes the streaming tail and releases the worker for the next client.
     */
    suspend fun finish(): QwenAsrTranscription
    {
        check(terminal.compareAndSet(false, true)) { "The Qwen3-ASR session is already finished." }
        return client.finishSession(lockOwner)
    }

    /**
     * Drops buffered audio without producing a transcript.
     */
    suspend fun cancel()
    {
        if (terminal.compareAndSet(false, true))
        {
            client.cancelSession(lockOwner)
        }
    }
}

/**
 * Owns one persistent WSL/CUDA Qwen3-ASR process and grants it to one streaming session at a time.
 */
class QwenAsrWorkerClient private constructor(
    private val configuration: QwenAsrWorkerConfiguration,
    private val process: Process,
    private val workerInput: DataInputStream,
    private val workerOutput: DataOutputStream
) : AutoCloseable
{
    private val requestMutex = Mutex()

    @Volatile
    private var closed = false

    private val diagnosticDrainThread = Thread(
        { process.errorStream.use { diagnosticStream -> diagnosticStream.copyTo(java.io.OutputStream.nullOutputStream()) } },
        "ClearDictate Qwen3-ASR diagnostic drain"
    ).apply {
        isDaemon = true
        start()
    }

    /**
     * Reserves the single official Qwen streaming state until finish or cancellation.
     */
    suspend fun startSession(): QwenAsrWorkerSession
    {
        val lockOwner = Any()
        requestMutex.lock(lockOwner)
        try
        {
            ensureOpen()
            exchangeEmptyRequest(REQUEST_BEGIN, RESPONSE_SESSION_STARTED, "QWEN_ASR_BEGIN_TIMEOUT")
            return QwenAsrWorkerSession(this, lockOwner)
        }
        catch (throwable: Throwable)
        {
            requestMutex.unlock(lockOwner)
            discardAfterFailure(throwable)
        }
    }

    /**
     * Exercises the same two-second streaming path used during a real utterance.
     */
    suspend fun warmUp()
    {
        requestMutex.withLock {
            ensureOpen()
            try
            {
                exchangeEmptyRequest(REQUEST_WARM_UP, RESPONSE_WARMED, "QWEN_ASR_WARM_UP_TIMEOUT")
            }
            catch (throwable: Throwable)
            {
                discardAfterFailure(throwable)
            }
        }
    }

    internal suspend fun acceptAudio(lockOwner: Any, capturedAudio: CapturedAudio)
    {
        check(requestMutex.holdsLock(lockOwner)) { "The Qwen3-ASR session no longer owns the worker." }
        val payload = CapturedAudioPayloadCodec.encode(capturedAudio)
        try
        {
            exchangeRequest(REQUEST_AUDIO, payload, RESPONSE_AUDIO_ACCEPTED, "QWEN_ASR_AUDIO_TIMEOUT")
        }
        catch (throwable: Throwable)
        {
            requestMutex.unlock(lockOwner)
            discardAfterFailure(throwable)
        }
        finally
        {
            payload.fill(0)
        }
    }

    internal suspend fun finishSession(lockOwner: Any): QwenAsrTranscription
    {
        check(requestMutex.holdsLock(lockOwner)) { "The Qwen3-ASR session no longer owns the worker." }
        return try
        {
            ensureOpen()
            val response = exchange(REQUEST_FINISH, ByteArray(0), "QWEN_ASR_FINISH_TIMEOUT")
            try
            {
                when (response.type)
                {
                    RESPONSE_TRANSCRIPT -> decodeTranscription(response.payload)
                    RESPONSE_ERROR -> throw LocalInferenceException(InferenceFailureCategory.NATIVE_FAILURE, "QWEN_ASR_INFERENCE_FAILED")
                    else -> throw LocalInferenceException(InferenceFailureCategory.PROTOCOL_FAILURE, "UNEXPECTED_QWEN_ASR_FINISH_RESPONSE")
                }
            }
            finally
            {
                response.payload.fill(0)
            }
        }
        catch (throwable: Throwable)
        {
            discardAfterFailure(throwable)
        }
        finally
        {
            if (requestMutex.holdsLock(lockOwner))
            {
                requestMutex.unlock(lockOwner)
            }
        }
    }

    internal suspend fun cancelSession(lockOwner: Any)
    {
        check(requestMutex.holdsLock(lockOwner)) { "The Qwen3-ASR session no longer owns the worker." }
        try
        {
            exchangeEmptyRequest(REQUEST_CANCEL, RESPONSE_CANCELLED, "QWEN_ASR_CANCEL_TIMEOUT")
        }
        catch (throwable: Throwable)
        {
            discardAfterFailure(throwable)
        }
        finally
        {
            if (requestMutex.holdsLock(lockOwner))
            {
                requestMutex.unlock(lockOwner)
            }
        }
    }

    @Synchronized
    override fun close()
    {
        if (closed)
        {
            return
        }
        closed = true
        if (process.isAlive)
        {
            runCatching { writeFrame(REQUEST_SHUTDOWN, ByteArray(0)) }
            if (!runCatching { process.waitFor(2, TimeUnit.SECONDS) }.getOrDefault(false))
            {
                process.destroy()
                if (!runCatching { process.waitFor(2, TimeUnit.SECONDS) }.getOrDefault(false))
                {
                    process.destroyForcibly()
                }
            }
        }
        runCatching { workerOutput.close() }
        runCatching { workerInput.close() }
    }

    private suspend fun exchangeEmptyRequest(requestType: Int, expectedResponseType: Int, timeoutCode: String)
    {
        val response = exchange(requestType, ByteArray(0), timeoutCode)
        try
        {
            if (response.type == RESPONSE_ERROR)
            {
                throw LocalInferenceException(InferenceFailureCategory.NATIVE_FAILURE, "QWEN_ASR_INFERENCE_FAILED")
            }
            if (response.type != expectedResponseType || response.payload.isNotEmpty())
            {
                throw LocalInferenceException(InferenceFailureCategory.PROTOCOL_FAILURE, "UNEXPECTED_QWEN_ASR_RESPONSE")
            }
        }
        finally
        {
            response.payload.fill(0)
        }
    }

    private suspend fun exchangeRequest(requestType: Int, payload: ByteArray, expectedResponseType: Int, timeoutCode: String)
    {
        val response = exchange(requestType, payload, timeoutCode)
        try
        {
            if (response.type == RESPONSE_ERROR)
            {
                throw LocalInferenceException(InferenceFailureCategory.NATIVE_FAILURE, "QWEN_ASR_INFERENCE_FAILED")
            }
            if (response.type != expectedResponseType || response.payload.isNotEmpty())
            {
                throw LocalInferenceException(InferenceFailureCategory.PROTOCOL_FAILURE, "UNEXPECTED_QWEN_ASR_RESPONSE")
            }
        }
        finally
        {
            response.payload.fill(0)
        }
    }

    private suspend fun exchange(requestType: Int, payload: ByteArray, timeoutCode: String): QwenAsrFrame
    {
        ensureOpen()
        return withContext(Dispatchers.IO)
        {
            writeFrame(requestType, payload)
            readFrameWithTimeout(configuration.transcriptionTimeoutMilliseconds, timeoutCode)
        }
    }

    private fun writeFrame(type: Int, payload: ByteArray)
    {
        workerOutput.writeInt(PROTOCOL_MAGIC)
        workerOutput.writeShort(PROTOCOL_VERSION)
        workerOutput.writeByte(type)
        workerOutput.writeInt(payload.size)
        workerOutput.write(payload)
        workerOutput.flush()
    }

    private fun readFrame(): QwenAsrFrame
    {
        if (workerInput.readInt() != PROTOCOL_MAGIC || workerInput.readUnsignedShort() != PROTOCOL_VERSION)
        {
            throw LocalInferenceException(InferenceFailureCategory.PROTOCOL_FAILURE, "INVALID_QWEN_ASR_FRAME_HEADER")
        }
        val type = workerInput.readUnsignedByte()
        val payloadLength = workerInput.readInt()
        if (payloadLength < 0 || payloadLength > MAXIMUM_RESPONSE_BYTES)
        {
            throw LocalInferenceException(InferenceFailureCategory.PROTOCOL_FAILURE, "INVALID_QWEN_ASR_PAYLOAD_LENGTH")
        }
        val payload = ByteArray(payloadLength)
        workerInput.readFully(payload)
        return QwenAsrFrame(type, payload)
    }

    /**
     * Bounds a blocking pipe read and tears down the WSL process if it stops producing protocol responses.
     */
    private fun readFrameWithTimeout(timeoutMilliseconds: Long, diagnosticCode: String): QwenAsrFrame
    {
        val pendingRead = CompletableFuture.supplyAsync(::readFrame)
        return try
        {
            pendingRead.get(timeoutMilliseconds, TimeUnit.MILLISECONDS)
        }
        catch (_: TimeoutException)
        {
            close()
            throw LocalInferenceException(InferenceFailureCategory.TIMEOUT, diagnosticCode)
        }
        catch (failure: ExecutionException)
        {
            val cause = failure.cause
            if (cause is LocalInferenceException)
            {
                throw cause
            }
            throw LocalInferenceException(InferenceFailureCategory.PROCESS_DIED, "QWEN_ASR_PIPE_READ_FAILED")
        }
    }

    private fun ensureOpen()
    {
        if (closed || !process.isAlive)
        {
            throw LocalInferenceException(InferenceFailureCategory.PROCESS_DIED, "QWEN_ASR_WORKER_CLOSED")
        }
    }

    private fun decodeTranscription(payload: ByteArray): QwenAsrTranscription
    {
        if (payload.size < PROCESSING_DURATION_BYTE_COUNT)
        {
            throw LocalInferenceException(InferenceFailureCategory.PROTOCOL_FAILURE, "INVALID_QWEN_ASR_TRANSCRIPT_PAYLOAD")
        }
        val processingNanoseconds = ByteBuffer.wrap(payload, 0, PROCESSING_DURATION_BYTE_COUNT).order(ByteOrder.BIG_ENDIAN).long
        if (processingNanoseconds < 0)
        {
            throw LocalInferenceException(InferenceFailureCategory.PROTOCOL_FAILURE, "INVALID_QWEN_ASR_PROCESSING_DURATION")
        }
        return QwenAsrTranscription(
            transcript = String(payload, PROCESSING_DURATION_BYTE_COUNT, payload.size - PROCESSING_DURATION_BYTE_COUNT, Charsets.UTF_8),
            processingMilliseconds = processingNanoseconds / NANOSECONDS_PER_MILLISECOND
        )
    }

    private fun discardAfterFailure(throwable: Throwable): Nothing
    {
        close()
        if (throwable is LocalInferenceException)
        {
            throw throwable
        }
        throw LocalInferenceException(InferenceFailureCategory.PROCESS_DIED, "QWEN_ASR_WORKER_STOPPED")
    }

    private data class QwenAsrFrame(val type: Int, val payload: ByteArray)

    companion object
    {
        private const val PROTOCOL_MAGIC = 0x43445141
        private const val PROTOCOL_VERSION = 3
        private const val REQUEST_BEGIN = 1
        private const val REQUEST_AUDIO = 2
        private const val REQUEST_FINISH = 3
        private const val REQUEST_CANCEL = 4
        private const val REQUEST_SHUTDOWN = 5
        private const val REQUEST_WARM_UP = 6
        private const val RESPONSE_READY = 1
        private const val RESPONSE_SESSION_STARTED = 2
        private const val RESPONSE_AUDIO_ACCEPTED = 3
        private const val RESPONSE_TRANSCRIPT = 4
        private const val RESPONSE_CANCELLED = 5
        private const val RESPONSE_WARMED = 6
        private const val RESPONSE_ERROR = 7
        private const val MAXIMUM_RESPONSE_BYTES = 64 * 1024
        private const val PROCESSING_DURATION_BYTE_COUNT = Long.SIZE_BYTES
        private const val NANOSECONDS_PER_MILLISECOND = 1_000_000L
        private const val WSL_WORKER_LAUNCH_COMMAND = "exec \"\$HOME/.local/share/cleardictate/venv/bin/python\" \"\$@\""

        /**
         * Starts the pinned WSL worker and returns only after model verification and CUDA loading complete.
         */
        suspend fun start(configuration: QwenAsrWorkerConfiguration): QwenAsrWorkerClient
        {
            val wslExecutable = configuration.wslExecutable.toAbsolutePath().normalize()
            val workerScript = configuration.workerScript.toAbsolutePath().normalize()
            val modelLock = configuration.modelLock.toAbsolutePath().normalize()
            require(Files.isRegularFile(wslExecutable)) { "The configured WSL executable does not exist." }
            require(configuration.wslDistribution.isNotBlank()) { "The WSL distribution name is empty." }
            require(Files.isRegularFile(workerScript)) { "The Qwen3-ASR worker script does not exist." }
            require(Files.isRegularFile(modelLock)) { "The Qwen3-ASR model lock does not exist." }
            require(configuration.startupTimeoutMilliseconds > 0) { "The Qwen3-ASR startup timeout must be positive." }
            require(configuration.transcriptionTimeoutMilliseconds > 0) { "The Qwen3-ASR request timeout must be positive." }

            return withContext(Dispatchers.IO)
            {
                val process = ProcessBuilder(
                    wslExecutable.toString(),
                    "-d",
                    configuration.wslDistribution,
                    "--exec",
                    "/bin/sh",
                    "-lc",
                    WSL_WORKER_LAUNCH_COMMAND,
                    "cleardictate-asr-worker",
                    windowsPathToWsl(workerScript),
                    windowsPathToWsl(modelLock)
                ).start()
                val workerInput = DataInputStream(BufferedInputStream(process.inputStream))
                val workerOutput = DataOutputStream(BufferedOutputStream(process.outputStream))
                val client = QwenAsrWorkerClient(configuration.copy(wslExecutable = wslExecutable, workerScript = workerScript, modelLock = modelLock), process, workerInput, workerOutput)
                try
                {
                    val readyFrame = client.readFrameWithTimeout(configuration.startupTimeoutMilliseconds, "QWEN_ASR_STARTUP_TIMEOUT")
                    try
                    {
                        if (readyFrame.type != RESPONSE_READY || readyFrame.payload.isNotEmpty())
                        {
                            throw LocalInferenceException(InferenceFailureCategory.PROTOCOL_FAILURE, "QWEN_ASR_WORKER_NOT_READY")
                        }
                    }
                    finally
                    {
                        readyFrame.payload.fill(0)
                    }
                    client
                }
                catch (throwable: Throwable)
                {
                    client.close()
                    throw throwable
                }
            }
        }

        /**
         * Converts a local drive path to WSL's standard mount without invoking a shell.
         */
        internal fun windowsPathToWsl(path: Path): String
        {
            val normalized = path.toAbsolutePath().normalize().toString()
            require(normalized.length >= 3 && normalized[1] == ':' && normalized[2] == '\\') { "ClearDictate WSL files must be on a local Windows drive." }
            val drive = normalized[0].lowercaseChar()
            return "/mnt/$drive/" + normalized.substring(3).replace('\\', '/')
        }
    }
}
