package com.cleardictate.desktop.inference

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.file.Path

data class WorkerSpeechModelLoadConfiguration(
    val modelDirectory: Path
)

enum class WorkerSpeechModelLoadPayloadFailure
{
    INVALID_DIRECTORY
}

class WorkerSpeechModelLoadPayloadException(
    val category: WorkerSpeechModelLoadPayloadFailure
) : IllegalArgumentException("Worker speech-model-load payload failure: $category")

/**
 * Encodes the versioned Moonshine model-directory payload consumed by the native speech worker.
 */
object WorkerSpeechModelLoadPayloadCodec
{
    private const val MAGIC = 0x4344534C
    private const val VERSION = 1
    private const val MAXIMUM_MODEL_DIRECTORY_BYTES = 4_000

    fun encode(configuration: WorkerSpeechModelLoadConfiguration): ByteArray
    {
        val normalizedModelDirectory = configuration.modelDirectory.toAbsolutePath().normalize().toString()
        val modelDirectoryBytes = normalizedModelDirectory.toByteArray(Charsets.UTF_8)
        if (modelDirectoryBytes.isEmpty() ||
            modelDirectoryBytes.size > MAXIMUM_MODEL_DIRECTORY_BYTES ||
            normalizedModelDirectory.contains('\u0000') ||
            normalizedModelDirectory.any { character -> character.code > 0x7F })
        {
            throw WorkerSpeechModelLoadPayloadException(WorkerSpeechModelLoadPayloadFailure.INVALID_DIRECTORY)
        }

        return ByteArrayOutputStream(10 + modelDirectoryBytes.size).use { payloadBytes ->
            DataOutputStream(payloadBytes).use { payload ->
                payload.writeInt(MAGIC)
                payload.writeShort(VERSION)
                payload.writeInt(modelDirectoryBytes.size)
                payload.write(modelDirectoryBytes)
            }
            payloadBytes.toByteArray()
        }
    }
}
