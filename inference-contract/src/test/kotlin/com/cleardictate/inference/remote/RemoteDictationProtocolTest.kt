package com.cleardictate.inference.remote

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Locks the completed-audio bytes shared by the Android client and PC service.
 */
class RemoteDictationProtocolTest
{
    @Test
    fun `round trips the versioned PCM16 payload`()
    {
        val audio = RemotePcmAudio(
            sampleRateHertz = RemoteDictationProtocol.SAMPLE_RATE_HERTZ,
            samples = shortArrayOf(0, 8_192, -16_384)
        )

        val payload = RemoteDictationProtocol.encodeAudio(audio)
        val decoded = RemoteDictationProtocol.decodeAudio(payload)

        assertEquals(RemoteDictationProtocol.SAMPLE_RATE_HERTZ, decoded.sampleRateHertz)
        assertContentEquals(audio.samples, decoded.samples)
        assertContentEquals(
            byteArrayOf(
                0x43, 0x44, 0x52, 0x41,
                0x00, 0x01,
                0x00, 0x01,
                0x00, 0x00, 0x3E, 0x80.toByte(),
                0x00, 0x00, 0x00, 0x03,
                0x00, 0x00,
                0x20, 0x00,
                0xC0.toByte(), 0x00
            ),
            payload
        )
    }

    @Test
    fun `rejects an out of range declared sample count before allocation`()
    {
        val payload = RemoteDictationProtocol.encodeAudio(
            RemotePcmAudio(RemoteDictationProtocol.SAMPLE_RATE_HERTZ, shortArrayOf(1))
        )
        payload[12] = 0x7F

        val failure = assertFailsWith<RemoteAudioPayloadException> {
            RemoteDictationProtocol.decodeAudio(payload)
        }

        assertEquals(RemoteAudioPayloadFailure.INVALID_SAMPLE_COUNT, failure.failure)
    }
}
