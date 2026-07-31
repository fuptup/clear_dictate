package com.cleardictate.desktop.inference

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Verifies the host-side endpoint payload consumed by the native speech worker.
 */
class WorkerRecordingStartPayloadCodecTest
{
    @Test
    fun `encodes default endpoint as an explicit versioned empty identifier`()
    {
        val payload = WorkerRecordingStartPayloadCodec.encode(
            WorkerRecordingStartConfiguration(endpointIdentifier = "")
        )

        DataInputStream(ByteArrayInputStream(payload)).use { input ->
            assertEquals(0x43445253, input.readInt())
            assertEquals(1, input.readUnsignedShort())
            assertEquals(0, input.readInt())
            assertEquals(0, input.available())
        }
    }

    @Test
    fun `encodes selected Unicode endpoint identifier as strict UTF8`()
    {
        val endpointIdentifier = "Microphone é"
        val payload = WorkerRecordingStartPayloadCodec.encode(
            WorkerRecordingStartConfiguration(endpointIdentifier)
        )
        val expectedPayload = byteArrayOf(
            0x43, 0x44, 0x52, 0x53,
            0x00, 0x01,
            0x00, 0x00, 0x00, 0x0D,
            'M'.code.toByte(), 'i'.code.toByte(), 'c'.code.toByte(), 'r'.code.toByte(), 'o'.code.toByte(),
            'p'.code.toByte(), 'h'.code.toByte(), 'o'.code.toByte(), 'n'.code.toByte(), 'e'.code.toByte(), ' '.code.toByte(),
            0xC3.toByte(), 0xA9.toByte()
        )
        assertContentEquals(expectedPayload, payload)

        DataInputStream(ByteArrayInputStream(payload)).use { input ->
            input.readInt()
            input.readUnsignedShort()
            val identifierBytes = ByteArray(input.readInt())
            input.readFully(identifierBytes)
            assertEquals(endpointIdentifier, identifierBytes.toString(Charsets.UTF_8))
        }
    }

    @Test
    fun `rejects malformed surrogate in endpoint identifier`()
    {
        assertFailsWith<WorkerRecordingStartPayloadException> {
            WorkerRecordingStartPayloadCodec.encode(
                WorkerRecordingStartConfiguration("Microphone \uD800")
            )
        }
    }
}
