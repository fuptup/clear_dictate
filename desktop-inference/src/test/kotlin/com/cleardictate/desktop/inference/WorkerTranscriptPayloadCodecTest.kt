package com.cleardictate.desktop.inference

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WorkerTranscriptPayloadCodecTest
{
    @Test
    fun `matches the native golden contract`()
    {
        val delta = WorkerTranscriptDelta(
            lineIdentifier = 42,
            isNew = true,
            isUpdated = false,
            isComplete = true,
            text = "Hello"
        )
        val expected = byteArrayOf(
            0x43, 0x44, 0x54, 0x44,
            0x00, 0x01,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x2A,
            0x05,
            0x00, 0x00, 0x00, 0x05,
            0x48, 0x65, 0x6C, 0x6C, 0x6F
        )

        assertContentEquals(expected, WorkerTranscriptPayloadCodec.encode(delta))
        assertEquals(delta, WorkerTranscriptPayloadCodec.decode(expected))
    }

    @Test
    fun `rejects unknown flags`()
    {
        val encoded = WorkerTranscriptPayloadCodec.encode(
            WorkerTranscriptDelta(1, isNew = true, isUpdated = false, isComplete = false, text = "text")
        )
        encoded[14] = 0x80.toByte()

        assertFailsWith<WorkerTranscriptPayloadException> {
            WorkerTranscriptPayloadCodec.decode(encoded)
        }
    }

    @Test
    fun `preserves an opaque line identifier with the high bit set`()
    {
        val delta = WorkerTranscriptDelta(
            lineIdentifier = Long.MIN_VALUE,
            isNew = true,
            isUpdated = false,
            isComplete = false,
            text = "opaque"
        )

        assertEquals(delta, WorkerTranscriptPayloadCodec.decode(WorkerTranscriptPayloadCodec.encode(delta)))
    }

    @Test
    fun `rejects sticky completion state without a line change`()
    {
        assertFailsWith<IllegalArgumentException> {
            WorkerTranscriptDelta(
                lineIdentifier = 1,
                isNew = false,
                isUpdated = false,
                isComplete = true,
                text = "stale"
            )
        }
    }

    @Test
    fun `rejects every truncated prefix and trailing bytes`()
    {
        val encoded = WorkerTranscriptPayloadCodec.encode(
            WorkerTranscriptDelta(1, isNew = true, isUpdated = false, isComplete = false, text = "sensitive")
        )

        for (truncatedLength in encoded.indices)
        {
            assertFailsWith<WorkerTranscriptPayloadException> {
                WorkerTranscriptPayloadCodec.decode(encoded.copyOf(truncatedLength))
            }
        }
        assertFailsWith<WorkerTranscriptPayloadException> {
            WorkerTranscriptPayloadCodec.decode(encoded + byteArrayOf(0))
        }
    }

    @Test
    fun `supports empty recognition text but rejects null text and oversized text`()
    {
        val emptyTextDelta = WorkerTranscriptDelta(1, isNew = true, isUpdated = false, isComplete = false, text = "")
        assertEquals(emptyTextDelta, WorkerTranscriptPayloadCodec.decode(WorkerTranscriptPayloadCodec.encode(emptyTextDelta)))

        assertFailsWith<IllegalArgumentException> {
            WorkerTranscriptDelta(1, isNew = true, isUpdated = false, isComplete = false, text = "bad\u0000text")
        }
        assertFailsWith<WorkerTranscriptPayloadException> {
            WorkerTranscriptPayloadCodec.encode(
                WorkerTranscriptDelta(1, isNew = true, isUpdated = false, isComplete = false, text = "x".repeat(65_518))
            )
        }
    }
}
