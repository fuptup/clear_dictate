package com.cleardictate.desktop.inference

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Path

data class WorkerModelLoadConfiguration(
    val modelPath: Path,
    val inferenceThreadCount: Int
)

enum class WorkerModelLoadPayloadFailure
{
    INVALID_THREAD_COUNT,
    INVALID_PATH
}

class WorkerModelLoadPayloadException(
    val category: WorkerModelLoadPayloadFailure
) : IllegalArgumentException("Worker model-load payload failure: $category")

/**
 * Encodes the versioned model-load payload shared with the native Windows worker.
 */
object WorkerModelLoadPayloadCodec
{
    private const val MAGIC = 0x43444D4C
    private const val VERSION = 1
    private const val MAXIMUM_MODEL_PATH_BYTES = 4_000
    private const val MAXIMUM_INFERENCE_THREAD_COUNT = 64

    fun encode(configuration: WorkerModelLoadConfiguration): ByteArray
    {
        if (configuration.inferenceThreadCount !in 1..MAXIMUM_INFERENCE_THREAD_COUNT)
        {
            throw WorkerModelLoadPayloadException(WorkerModelLoadPayloadFailure.INVALID_THREAD_COUNT)
        }

        val normalizedModelPath = configuration.modelPath.toAbsolutePath().normalize().toString()
        val modelPathBytes = normalizedModelPath.toByteArray(Charsets.UTF_8)
        if (modelPathBytes.isEmpty() ||
            modelPathBytes.size > MAXIMUM_MODEL_PATH_BYTES ||
            normalizedModelPath.contains('\u0000'))
        {
            throw WorkerModelLoadPayloadException(WorkerModelLoadPayloadFailure.INVALID_PATH)
        }

        return ByteArrayOutputStream(12 + modelPathBytes.size).use { payloadBytes ->
            DataOutputStream(payloadBytes).use { payload ->
                payload.writeInt(MAGIC)
                payload.writeShort(VERSION)
                payload.writeShort(configuration.inferenceThreadCount)
                payload.writeInt(modelPathBytes.size)
                payload.write(modelPathBytes)
            }
            payloadBytes.toByteArray()
        }
    }
}

/**
 * Decodes transcript-free four-byte error categories returned by the worker.
 */
object WorkerErrorPayloadCodec
{
    fun decode(payload: WorkerPayload): Int
    {
        val bytes = payload.copyBytes()
        require(bytes.size == Int.SIZE_BYTES) { "A worker error payload must contain exactly four bytes." }
        return ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).int
    }
}
