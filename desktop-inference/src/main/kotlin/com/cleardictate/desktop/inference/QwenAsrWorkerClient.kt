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
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

data class QwenAsrWorkerConfiguration(
    val pythonExecutable: Path,
    val workerScript: Path,
    val modelDirectory: Path,
    val modelLock: Path,
    val startupTimeoutMilliseconds: Long = 180_000,
    val transcriptionTimeoutMilliseconds: Long = 120_000
)

/**
 * Owns one persistent CUDA Qwen3-ASR process and serializes release-triggered transcription requests.
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
     * Sends one completed in-memory recording and waits for its final transcript.
     */
    suspend fun transcribe(capturedAudio: CapturedAudio): String
    {
        if (capturedAudio.samples.isEmpty())
        {
            return ""
        }

        return requestMutex.withLock {
            ensureOpen()
            val payload = CapturedAudioPayloadCodec.encode(capturedAudio)
            try
            {
                withContext(Dispatchers.IO)
                {
                    writeFrame(REQUEST_TRANSCRIBE, payload)
                    val response = readFrameWithTimeout(configuration.transcriptionTimeoutMilliseconds, "QWEN_ASR_TRANSCRIPTION_TIMEOUT")
                    try
                    {
                        when (response.type)
                        {
                            RESPONSE_TRANSCRIPT -> response.payload.toString(Charsets.UTF_8)
                            RESPONSE_ERROR -> throw LocalInferenceException(InferenceFailureCategory.NATIVE_FAILURE, "QWEN_ASR_INFERENCE_FAILED")
                            else -> throw LocalInferenceException(InferenceFailureCategory.PROTOCOL_FAILURE, "UNEXPECTED_QWEN_ASR_RESPONSE")
                        }
                    }
                    finally
                    {
                        response.payload.fill(0)
                    }
                }
            }
            catch (failure: LocalInferenceException)
            {
                throw failure
            }
            catch (_: Exception)
            {
                close()
                throw LocalInferenceException(InferenceFailureCategory.PROCESS_DIED, "QWEN_ASR_WORKER_STOPPED")
            }
            finally
            {
                payload.fill(0)
            }
        }
    }

    /**
     * Exercises ASR prefill and one decode step with synthetic worker-owned audio, avoiding a first-dictation CUDA penalty without retaining speech data.
     */
    suspend fun warmUp()
    {
        requestMutex.withLock {
            ensureOpen()
            try
            {
                withContext(Dispatchers.IO)
                {
                    writeFrame(REQUEST_WARM_UP, ByteArray(0))
                    val response = readFrameWithTimeout(configuration.transcriptionTimeoutMilliseconds, "QWEN_ASR_WARM_UP_TIMEOUT")
                    try
                    {
                        if (response.type != RESPONSE_WARMED || response.payload.isNotEmpty())
                        {
                            throw LocalInferenceException(InferenceFailureCategory.PROTOCOL_FAILURE, "UNEXPECTED_QWEN_ASR_WARM_UP_RESPONSE")
                        }
                    }
                    finally
                    {
                        response.payload.fill(0)
                    }
                }
            }
            catch (failure: LocalInferenceException)
            {
                throw failure
            }
            catch (_: Exception)
            {
                close()
                throw LocalInferenceException(InferenceFailureCategory.PROCESS_DIED, "QWEN_ASR_WARM_UP_STOPPED")
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
     * Bounds a blocking pipe read and tears down the worker if it stops producing protocol responses.
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

    private data class QwenAsrFrame(val type: Int, val payload: ByteArray)

    companion object
    {
        private const val PROTOCOL_MAGIC = 0x43445141
        private const val PROTOCOL_VERSION = 1
        private const val REQUEST_TRANSCRIBE = 1
        private const val REQUEST_SHUTDOWN = 2
        private const val REQUEST_WARM_UP = 3
        private const val RESPONSE_READY = 1
        private const val RESPONSE_TRANSCRIPT = 2
        private const val RESPONSE_ERROR = 3
        private const val RESPONSE_WARMED = 4
        private const val MAXIMUM_RESPONSE_BYTES = 64 * 1024

        /**
         * Starts the pinned worker and returns only after model verification and CUDA loading complete.
         */
        suspend fun start(configuration: QwenAsrWorkerConfiguration): QwenAsrWorkerClient
        {
            val pythonExecutable = configuration.pythonExecutable.toAbsolutePath().normalize()
            val workerScript = configuration.workerScript.toAbsolutePath().normalize()
            val modelDirectory = configuration.modelDirectory.toAbsolutePath().normalize()
            val modelLock = configuration.modelLock.toAbsolutePath().normalize()
            require(Files.isRegularFile(pythonExecutable)) { "The configured Python executable does not exist." }
            require(Files.isRegularFile(workerScript)) { "The Qwen3-ASR worker script does not exist." }
            require(Files.isDirectory(modelDirectory)) { "The Qwen3-ASR model directory does not exist." }
            require(Files.isRegularFile(modelLock)) { "The Qwen3-ASR model lock does not exist." }
            require(configuration.startupTimeoutMilliseconds > 0) { "The Qwen3-ASR startup timeout must be positive." }
            require(configuration.transcriptionTimeoutMilliseconds > 0) { "The Qwen3-ASR transcription timeout must be positive." }

            return withContext(Dispatchers.IO)
            {
                val process = ProcessBuilder(pythonExecutable.toString(), workerScript.toString(), modelDirectory.toString(), modelLock.toString()).start()
                val workerInput = DataInputStream(BufferedInputStream(process.inputStream))
                val workerOutput = DataOutputStream(BufferedOutputStream(process.outputStream))
                val normalizedConfiguration = configuration.copy(
                    pythonExecutable = pythonExecutable,
                    workerScript = workerScript,
                    modelDirectory = modelDirectory,
                    modelLock = modelLock
                )
                val client = QwenAsrWorkerClient(normalizedConfiguration, process, workerInput, workerOutput)
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
    }
}
