package com.cleardictate.desktop.inference

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WorkerModelLoadPayloadCodecTest
{
    @Test
    fun `model payload matches the native big-endian wire format`()
    {
        val absolutePath = Path.of("C:\\models\\qwen.gguf").toAbsolutePath().normalize()
        val pathBytes = absolutePath.toString().toByteArray(Charsets.UTF_8)

        val expectedPrefix = byteArrayOf(
            0x43,
            0x44,
            0x4D,
            0x4C,
            0x00,
            0x01,
            0x00,
            0x04,
            ((pathBytes.size ushr 24) and 0xFF).toByte(),
            ((pathBytes.size ushr 16) and 0xFF).toByte(),
            ((pathBytes.size ushr 8) and 0xFF).toByte(),
            (pathBytes.size and 0xFF).toByte()
        )

        val encoded = WorkerModelLoadPayloadCodec.encode(
            WorkerModelLoadConfiguration(absolutePath, inferenceThreadCount = 4)
        )

        assertContentEquals(expectedPrefix + pathBytes, encoded)
    }

    @Test
    fun `thread count outside the native bound is rejected`()
    {
        val exception = assertFailsWith<WorkerModelLoadPayloadException> {
            WorkerModelLoadPayloadCodec.encode(
                WorkerModelLoadConfiguration(Path.of("C:\\models\\qwen.gguf"), inferenceThreadCount = 65)
            )
        }

        assertEquals(WorkerModelLoadPayloadFailure.INVALID_THREAD_COUNT, exception.category)
    }

    @Test
    fun `worker error category is decoded as a big-endian integer`()
    {
        val category = WorkerErrorPayloadCodec.decode(WorkerPayload.copyOf(byteArrayOf(0, 0, 1, 2)))

        assertEquals(258, category)
    }
}
