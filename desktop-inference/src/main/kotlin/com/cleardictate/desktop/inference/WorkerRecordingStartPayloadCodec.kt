package com.cleardictate.desktop.inference

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

data class WorkerRecordingStartConfiguration(
    val endpointIdentifier: String
)

enum class WorkerRecordingStartPayloadFailure
{
    INVALID_ENDPOINT_IDENTIFIER
}

class WorkerRecordingStartPayloadException(
    val category: WorkerRecordingStartPayloadFailure
) : IllegalArgumentException("Worker recording-start payload failure: $category")

/**
 * Encodes one exact Windows endpoint identifier. An empty identifier requests
 * the default console capture endpoint resolved once by the native worker.
 */
object WorkerRecordingStartPayloadCodec
{
    private const val MAGIC = 0x43445253
    private const val VERSION = 1
    private const val MAXIMUM_ENDPOINT_IDENTIFIER_BYTES = 4_000

    fun encode(configuration: WorkerRecordingStartConfiguration): ByteArray
    {
        if (configuration.endpointIdentifier.contains('\u0000'))
        {
            throw WorkerRecordingStartPayloadException(WorkerRecordingStartPayloadFailure.INVALID_ENDPOINT_IDENTIFIER)
        }

        val endpointIdentifierBytes = try
        {
            val encoder = Charsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            val encodedIdentifier = encoder.encode(CharBuffer.wrap(configuration.endpointIdentifier))
            ByteArray(encodedIdentifier.remaining()).also { bytes -> encodedIdentifier.get(bytes) }
        }
        catch (_: CharacterCodingException)
        {
            throw WorkerRecordingStartPayloadException(WorkerRecordingStartPayloadFailure.INVALID_ENDPOINT_IDENTIFIER)
        }

        if (endpointIdentifierBytes.size > MAXIMUM_ENDPOINT_IDENTIFIER_BYTES)
        {
            throw WorkerRecordingStartPayloadException(WorkerRecordingStartPayloadFailure.INVALID_ENDPOINT_IDENTIFIER)
        }

        return ByteArrayOutputStream(10 + endpointIdentifierBytes.size).use { payloadBytes ->
            DataOutputStream(payloadBytes).use { payload ->
                payload.writeInt(MAGIC)
                payload.writeShort(VERSION)
                payload.writeInt(endpointIdentifierBytes.size)
                payload.write(endpointIdentifierBytes)
            }
            payloadBytes.toByteArray()
        }
    }
}
