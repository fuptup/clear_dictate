package com.cleardictate.inference.remote

import java.io.DataInput
import java.io.DataOutput

enum class RemoteAudioPayloadFailure
{
    INVALID_HEADER,
    UNSUPPORTED_VERSION,
    UNSUPPORTED_SAMPLE_FORMAT,
    INVALID_SAMPLE_RATE,
    INVALID_SAMPLE_COUNT
}

class RemoteAudioPayloadException(
    val failure: RemoteAudioPayloadFailure
) : IllegalArgumentException("Remote-audio payload failure: $failure")

/**
 * Defines the sole versioned phone-to-PC transport: bounded framed mono PCM16 followed by an explicit release marker.
 */
object RemoteDictationProtocol
{
    private const val STREAM_MAGIC = 0x43445341
    private const val STREAM_VERSION: Short = 1
    private const val PCM16_SAMPLE_FORMAT: Short = 1

    const val DICTATION_PATH = "/v1/dictation"
    const val HEALTH_PATH = "/v1/health"
    const val HEALTH_STATE_HEADER = "X-ClearDictate-Service-State"
    const val HEALTH_STATE_READY = "ready"
    const val HEALTH_STATE_PREPARING_AI = "preparing-ai"
    const val STREAM_AUDIO_CONTENT_TYPE = "application/vnd.cleardictate.pcm16-stream"
    const val TEXT_CONTENT_TYPE = "text/plain; charset=utf-8"
    const val SAMPLE_RATE_HERTZ = 16_000
    const val MAXIMUM_SAMPLE_COUNT = SAMPLE_RATE_HERTZ * 60 * 5
    const val MAXIMUM_TRANSCRIPT_UTF8_BYTES = 32_000 * 4

    /**
     * Writes the fixed stream identity before any framed audio so the PC rejects incompatible senders before allocation.
     */
    fun writeStreamHeader(output: DataOutput)
    {
        output.writeInt(STREAM_MAGIC)
        output.writeShort(STREAM_VERSION.toInt())
        output.writeShort(PCM16_SAMPLE_FORMAT.toInt())
        output.writeInt(SAMPLE_RATE_HERTZ)
    }

    /**
     * Validates stream identity before the server starts an utterance.
     */
    fun readAndValidateStreamHeader(input: DataInput)
    {
        if (input.readInt() != STREAM_MAGIC)
        {
            throw RemoteAudioPayloadException(RemoteAudioPayloadFailure.INVALID_HEADER)
        }
        if (input.readShort() != STREAM_VERSION)
        {
            throw RemoteAudioPayloadException(RemoteAudioPayloadFailure.UNSUPPORTED_VERSION)
        }
        if (input.readShort() != PCM16_SAMPLE_FORMAT)
        {
            throw RemoteAudioPayloadException(RemoteAudioPayloadFailure.UNSUPPORTED_SAMPLE_FORMAT)
        }
        if (input.readInt() != SAMPLE_RATE_HERTZ)
        {
            throw RemoteAudioPayloadException(RemoteAudioPayloadFailure.INVALID_SAMPLE_RATE)
        }
    }

    /**
     * Writes one microphone buffer while preserving request boundaries independently from HTTP chunking.
     */
    fun writeAudioFrame(output: DataOutput, samples: ShortArray, sampleCount: Int)
    {
        if (sampleCount !in 1..samples.size)
        {
            throw RemoteAudioPayloadException(RemoteAudioPayloadFailure.INVALID_SAMPLE_COUNT)
        }
        output.writeInt(sampleCount)
        for (sampleIndex in 0 until sampleCount)
        {
            output.writeShort(samples[sampleIndex].toInt())
        }
    }

    /**
     * Writes the explicit successful end marker so network EOF remains distinguishable from finger release.
     */
    fun writeStreamFinish(output: DataOutput)
    {
        output.writeInt(0)
    }

    /**
     * Reads one allocation-bounded frame and returns null only for the explicit successful end marker.
     */
    fun readAudioFrame(input: DataInput, remainingSampleCount: Int = MAXIMUM_SAMPLE_COUNT): ShortArray?
    {
        val sampleCount = input.readInt()
        if (sampleCount == 0)
        {
            return null
        }
        if (sampleCount !in 1..remainingSampleCount)
        {
            throw RemoteAudioPayloadException(RemoteAudioPayloadFailure.INVALID_SAMPLE_COUNT)
        }
        return ShortArray(sampleCount) { input.readShort() }
    }
}
