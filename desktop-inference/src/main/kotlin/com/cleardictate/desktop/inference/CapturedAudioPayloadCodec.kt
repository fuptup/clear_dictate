package com.cleardictate.desktop.inference

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * One mono floating-point recording captured at its native worker's fixed sample rate.
 * The sample array is mutable so its owner can scrub it immediately after transcription.
 */
data class CapturedAudio(
    val sampleRate: Int,
    val samples: FloatArray
)
{
    init
    {
        require(sampleRate > 0) { "Captured audio requires a positive sample rate." }
    }
}

enum class CapturedAudioPayloadFailure
{
    INVALID_HEADER,
    UNSUPPORTED_VERSION,
    UNSUPPORTED_SAMPLE_FORMAT,
    INVALID_SAMPLE_RATE,
    INVALID_SAMPLE_COUNT
}

class CapturedAudioPayloadException(
    val failure: CapturedAudioPayloadFailure
) : IllegalArgumentException("Captured-audio payload failure: $failure")

/**
 * Decodes the compact cross-language payload emitted by the native Windows capture worker.
 * Header integers and IEEE 754 samples use network byte order so golden bytes are architecture-independent.
 */
object CapturedAudioPayloadCodec
{
    private const val HEADER_BYTE_COUNT = 16
    private const val MAGIC = 0x43444155
    private const val VERSION: Short = 1
    private const val FLOAT32_SAMPLE_FORMAT: Short = 1
    private const val BYTES_PER_SAMPLE = 4

    fun encode(capturedAudio: CapturedAudio): ByteArray
    {
        val payloadByteCount = HEADER_BYTE_COUNT.toLong() + capturedAudio.samples.size.toLong() * BYTES_PER_SAMPLE
        if (payloadByteCount > Int.MAX_VALUE)
        {
            throw CapturedAudioPayloadException(CapturedAudioPayloadFailure.INVALID_SAMPLE_COUNT)
        }

        val buffer = ByteBuffer.allocate(payloadByteCount.toInt()).order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(MAGIC)
        buffer.putShort(VERSION)
        buffer.putShort(FLOAT32_SAMPLE_FORMAT)
        buffer.putInt(capturedAudio.sampleRate)
        buffer.putInt(capturedAudio.samples.size)
        capturedAudio.samples.forEach(buffer::putFloat)
        return buffer.array()
    }

    fun decode(payload: ByteArray): CapturedAudio
    {
        if (payload.size < HEADER_BYTE_COUNT)
        {
            throw CapturedAudioPayloadException(CapturedAudioPayloadFailure.INVALID_HEADER)
        }

        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        if (buffer.int != MAGIC)
        {
            throw CapturedAudioPayloadException(CapturedAudioPayloadFailure.INVALID_HEADER)
        }
        if (buffer.short != VERSION)
        {
            throw CapturedAudioPayloadException(CapturedAudioPayloadFailure.UNSUPPORTED_VERSION)
        }
        if (buffer.short != FLOAT32_SAMPLE_FORMAT)
        {
            throw CapturedAudioPayloadException(CapturedAudioPayloadFailure.UNSUPPORTED_SAMPLE_FORMAT)
        }

        val sampleRate = buffer.int
        if (sampleRate <= 0)
        {
            throw CapturedAudioPayloadException(CapturedAudioPayloadFailure.INVALID_SAMPLE_RATE)
        }

        val sampleCount = buffer.int
        val expectedPayloadByteCount = HEADER_BYTE_COUNT.toLong() + sampleCount.toLong() * BYTES_PER_SAMPLE
        if (sampleCount < 0 || expectedPayloadByteCount != payload.size.toLong())
        {
            throw CapturedAudioPayloadException(CapturedAudioPayloadFailure.INVALID_SAMPLE_COUNT)
        }

        val samples = FloatArray(sampleCount)
        for (sampleIndex in samples.indices)
        {
            samples[sampleIndex] = buffer.float
        }
        return CapturedAudio(sampleRate = sampleRate, samples = samples)
    }
}
