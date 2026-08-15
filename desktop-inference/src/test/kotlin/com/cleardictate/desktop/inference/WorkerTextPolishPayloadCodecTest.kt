package com.cleardictate.desktop.inference

import com.cleardictate.domain.TranscriptPolishingRequest
import kotlin.test.Test
import kotlin.test.assertContentEquals

/**
 * Pins the prompt-role payload shared by the Kotlin host and native text worker.
 */
class WorkerTextPolishPayloadCodecTest
{
    @Test
    fun `polish payload preserves separate system and user roles in the native wire format`()
    {
        val request = TranscriptPolishingRequest(
            untrustedCleanTranscript = "not transported separately",
            systemInstruction = "system",
            userMessage = "user"
        )

        val encoded = WorkerTextPolishPayloadCodec.encode(request)

        assertContentEquals(
            byteArrayOf(
                0x43, 0x44, 0x54, 0x50,
                0x00, 0x01,
                0x00, 0x00, 0x00, 0x06,
                0x00, 0x00, 0x00, 0x04,
                's'.code.toByte(), 'y'.code.toByte(), 's'.code.toByte(), 't'.code.toByte(), 'e'.code.toByte(), 'm'.code.toByte(),
                'u'.code.toByte(), 's'.code.toByte(), 'e'.code.toByte(), 'r'.code.toByte()
            ),
            encoded
        )
    }
}
