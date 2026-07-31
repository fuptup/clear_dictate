package com.cleardictate.desktop.inference

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Verifies the host-side speech-model payload consumed by the native speech worker.
 */
class WorkerSpeechModelLoadPayloadCodecTest
{
    @Test
    fun `encodes versioned speech model directory payload`()
    {
        val payload = WorkerSpeechModelLoadPayloadCodec.encode(
            WorkerSpeechModelLoadConfiguration(Path.of("E:\\models\\moonshine"))
        )
        val expectedPayload = byteArrayOf(
            0x43, 0x44, 0x53, 0x4C,
            0x00, 0x01,
            0x00, 0x00, 0x00, 0x13,
            'E'.code.toByte(), ':'.code.toByte(), '\\'.code.toByte(),
            'm'.code.toByte(), 'o'.code.toByte(), 'd'.code.toByte(), 'e'.code.toByte(), 'l'.code.toByte(), 's'.code.toByte(), '\\'.code.toByte(),
            'm'.code.toByte(), 'o'.code.toByte(), 'o'.code.toByte(), 'n'.code.toByte(), 's'.code.toByte(), 'h'.code.toByte(), 'i'.code.toByte(), 'n'.code.toByte(), 'e'.code.toByte()
        )
        assertContentEquals(expectedPayload, payload)

        DataInputStream(ByteArrayInputStream(payload)).use { input ->
            assertEquals(0x4344534C, input.readInt())
            assertEquals(1, input.readUnsignedShort())

            val pathLength = input.readInt()
            val pathBytes = ByteArray(pathLength)
            input.readFully(pathBytes)

            assertEquals(
                Path.of("E:\\models\\moonshine").toAbsolutePath().normalize().toString(),
                pathBytes.toString(Charsets.UTF_8)
            )
            assertEquals(0, input.available())
        }
    }

    @Test
    fun `rejects oversized speech model directory`()
    {
        assertFailsWith<WorkerSpeechModelLoadPayloadException> {
            WorkerSpeechModelLoadPayloadCodec.encode(
                WorkerSpeechModelLoadConfiguration(Path.of("a".repeat(4_001)))
            )
        }
    }

    @Test
    fun `rejects non ASCII speech model directory`()
    {
        assertFailsWith<WorkerSpeechModelLoadPayloadException> {
            WorkerSpeechModelLoadPayloadCodec.encode(
                WorkerSpeechModelLoadConfiguration(Path.of("models", "moonshine-é"))
            )
        }
    }
}
