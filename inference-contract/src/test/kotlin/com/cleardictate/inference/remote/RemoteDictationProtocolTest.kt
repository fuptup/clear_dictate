package com.cleardictate.inference.remote

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Locks the streaming bytes shared by the Android client and PC service.
 */
class RemoteDictationProtocolTest
{
    @Test
    fun `stream header and audio frames round trip incrementally until the explicit finish frame`()
    {
        val payload = ByteArrayOutputStream()
        DataOutputStream(payload).use { output ->
            RemoteDictationProtocol.writeStreamHeader(output)
            RemoteDictationProtocol.writeAudioFrame(output, shortArrayOf(0, 8_192, -16_384), 3)
            RemoteDictationProtocol.writeAudioFrame(output, shortArrayOf(7, 99), 1)
            RemoteDictationProtocol.writeStreamFinish(output)
        }

        DataInputStream(ByteArrayInputStream(payload.toByteArray())).use { input ->
            RemoteDictationProtocol.readAndValidateStreamHeader(input)
            assertContentEquals(shortArrayOf(0, 8_192, -16_384), RemoteDictationProtocol.readAudioFrame(input))
            assertContentEquals(shortArrayOf(7), RemoteDictationProtocol.readAudioFrame(input))
            assertEquals(null, RemoteDictationProtocol.readAudioFrame(input))
        }
    }

    @Test
    fun `stream rejects a frame that would exceed the remaining recording boundary before allocation`()
    {
        val payload = ByteArrayOutputStream()
        DataOutputStream(payload).use { output ->
            output.writeInt(RemoteDictationProtocol.MAXIMUM_SAMPLE_COUNT)
        }

        val failure = assertFailsWith<RemoteAudioPayloadException> {
            DataInputStream(ByteArrayInputStream(payload.toByteArray())).use { input ->
                RemoteDictationProtocol.readAudioFrame(input, remainingSampleCount = 1)
            }
        }

        assertEquals(RemoteAudioPayloadFailure.INVALID_SAMPLE_COUNT, failure.failure)
    }
}
