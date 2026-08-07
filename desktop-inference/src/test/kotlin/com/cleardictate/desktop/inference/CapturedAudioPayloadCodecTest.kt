package com.cleardictate.desktop.inference

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Locks the native-to-Kotlin captured-audio payload used after push-to-talk release.
 */
class CapturedAudioPayloadCodecTest
{
    @Test
    fun `decodes the cross-language float audio payload`()
    {
        val payload = byteArrayOf(
            0x43, 0x44, 0x41, 0x55,
            0x00, 0x01,
            0x00, 0x01,
            0x00, 0x00, 0x3E, 0x80.toByte(),
            0x00, 0x00, 0x00, 0x03,
            0x00, 0x00, 0x00, 0x00,
            0x3E, 0x80.toByte(), 0x00, 0x00,
            0xBF.toByte(), 0x00, 0x00, 0x00
        )

        val capturedAudio = CapturedAudioPayloadCodec.decode(payload)

        assertEquals(16_000, capturedAudio.sampleRate)
        assertContentEquals(floatArrayOf(0.0F, 0.25F, -0.5F), capturedAudio.samples)
        assertContentEquals(payload, CapturedAudioPayloadCodec.encode(capturedAudio))
    }

    @Test
    fun `rejects a declared sample count that exceeds the payload`()
    {
        val malformedPayload = byteArrayOf(
            0x43, 0x44, 0x41, 0x55,
            0x00, 0x01,
            0x00, 0x01,
            0x00, 0x00, 0x3E, 0x80.toByte(),
            0x00, 0x00, 0x00, 0x01
        )

        assertFailsWith<CapturedAudioPayloadException> {
            CapturedAudioPayloadCodec.decode(malformedPayload)
        }
    }
}
