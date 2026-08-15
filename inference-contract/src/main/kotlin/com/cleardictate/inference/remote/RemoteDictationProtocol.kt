package com.cleardictate.inference.remote

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Owns one completed mono Pulse Code Modulation recording transferred from Android to the PC.
 * Samples remain mutable so each owner can erase them immediately after use.
 */
data class RemotePcmAudio(
    val sampleRateHertz: Int,
    val samples: ShortArray
)
{
    init
    {
        require(sampleRateHertz == RemoteDictationProtocol.SAMPLE_RATE_HERTZ) {
            "Remote dictation requires ${RemoteDictationProtocol.SAMPLE_RATE_HERTZ} Hz audio."
        }
        require(samples.isNotEmpty() && samples.size <= RemoteDictationProtocol.MAXIMUM_SAMPLE_COUNT) {
            "Remote dictation audio has an invalid sample count."
        }
    }
}

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
 * Defines the versioned phone-to-PC HTTP and binary-audio contract.
 * Five minutes matches the existing Android recording-session boundary and caps request allocation before decoding.
 */
object RemoteDictationProtocol
{
    private const val HEADER_BYTE_COUNT = 16
    private const val MAGIC = 0x43445241
    private const val VERSION: Short = 1
    private const val PCM16_SAMPLE_FORMAT: Short = 1
    private const val BYTES_PER_SAMPLE = 2

    const val DICTATION_PATH = "/v1/dictation"
    const val HEALTH_PATH = "/v1/health"
    const val HEALTH_STATE_HEADER = "X-ClearDictate-Service-State"
    const val HEALTH_STATE_READY = "ready"
    const val HEALTH_STATE_PREPARING_AI = "preparing-ai"
    const val AUDIO_CONTENT_TYPE = "application/vnd.cleardictate.pcm16"
    const val TEXT_CONTENT_TYPE = "text/plain; charset=utf-8"
    const val SAMPLE_RATE_HERTZ = 16_000
    const val MAXIMUM_SAMPLE_COUNT = SAMPLE_RATE_HERTZ * 60 * 5
    const val MAXIMUM_AUDIO_PAYLOAD_BYTES = HEADER_BYTE_COUNT + MAXIMUM_SAMPLE_COUNT * BYTES_PER_SAMPLE
    const val MAXIMUM_TRANSCRIPT_UTF8_BYTES = 32_000 * 4

    /**
     * Encodes signed PCM16 samples in network byte order so Android and desktop implementations share exact bytes.
     */
    fun encodeAudio(audio: RemotePcmAudio): ByteArray
    {
        val payload = ByteBuffer.allocate(HEADER_BYTE_COUNT + audio.samples.size * BYTES_PER_SAMPLE).order(ByteOrder.BIG_ENDIAN)
        payload.putInt(MAGIC)
        payload.putShort(VERSION)
        payload.putShort(PCM16_SAMPLE_FORMAT)
        payload.putInt(audio.sampleRateHertz)
        payload.putInt(audio.samples.size)
        audio.samples.forEach(payload::putShort)
        return payload.array()
    }

    /**
     * Validates the complete body before allocating its sample array.
     */
    fun decodeAudio(payload: ByteArray): RemotePcmAudio
    {
        if (payload.size < HEADER_BYTE_COUNT)
        {
            throw RemoteAudioPayloadException(RemoteAudioPayloadFailure.INVALID_HEADER)
        }

        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        if (buffer.int != MAGIC)
        {
            throw RemoteAudioPayloadException(RemoteAudioPayloadFailure.INVALID_HEADER)
        }
        if (buffer.short != VERSION)
        {
            throw RemoteAudioPayloadException(RemoteAudioPayloadFailure.UNSUPPORTED_VERSION)
        }
        if (buffer.short != PCM16_SAMPLE_FORMAT)
        {
            throw RemoteAudioPayloadException(RemoteAudioPayloadFailure.UNSUPPORTED_SAMPLE_FORMAT)
        }

        val sampleRateHertz = buffer.int
        if (sampleRateHertz != SAMPLE_RATE_HERTZ)
        {
            throw RemoteAudioPayloadException(RemoteAudioPayloadFailure.INVALID_SAMPLE_RATE)
        }

        val sampleCount = buffer.int
        val expectedPayloadBytes = HEADER_BYTE_COUNT.toLong() + sampleCount.toLong() * BYTES_PER_SAMPLE
        if (sampleCount !in 1..MAXIMUM_SAMPLE_COUNT || expectedPayloadBytes != payload.size.toLong())
        {
            throw RemoteAudioPayloadException(RemoteAudioPayloadFailure.INVALID_SAMPLE_COUNT)
        }

        val samples = ShortArray(sampleCount)
        for (sampleIndex in samples.indices)
        {
            samples[sampleIndex] = buffer.short
        }
        return RemotePcmAudio(sampleRateHertz = sampleRateHertz, samples = samples)
    }
}
