package com.cleardictate.desktop

import com.cleardictate.desktop.inference.CapturedAudio
import kotlinx.coroutines.test.runTest
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * Verifies that one successful dictation retains a portable audio file together with both model outputs and stage timings.
 */
class SqliteDesktopDictationHistoryTest
{
    @Test
    fun `stores WAV audio transcripts timings and UTC capture datetime`() = runTest {
        val databasePath = Files.createTempDirectory("cleardictate-history").resolve("history.sqlite")
        val history = SqliteDesktopDictationHistory.open(databasePath)
        val capturedAudio = CapturedAudio(sampleRate = 16_000, samples = floatArrayOf(0.0F, 0.5F, -1.0F))
        val recordedAt = Instant.parse("2026-08-10T21:00:00Z")
        val result = DesktopDictationResult(
            rawTranscript = "um send build AB12",
            polishedTranscript = "Send build AB12.",
            timing = DesktopDictationTiming(queueMilliseconds = 4, recognitionMilliseconds = 321, rewritingMilliseconds = 87, totalMilliseconds = 412)
        )

        history.record(recordedAt, capturedAudio, result)
        val entry = history.readSummaries().single()
        val wavAudio = requireNotNull(history.readWavAudio(entry.identifier))

        assertEquals(1L, entry.identifier)
        assertEquals(recordedAt, entry.recordedAt)
        assertEquals(result.rawTranscript, entry.rawTranscript)
        assertEquals(result.polishedTranscript, entry.polishedTranscript)
        assertEquals(result.timing, entry.timing)
        assertContentEquals("RIFF".encodeToByteArray(), wavAudio.copyOfRange(0, 4))
        assertContentEquals("WAVE".encodeToByteArray(), wavAudio.copyOfRange(8, 12))
        assertEquals(16_000, ByteBuffer.wrap(wavAudio).order(ByteOrder.LITTLE_ENDIAN).getInt(24))
        assertContentEquals(shortArrayOf(0, 16_384, Short.MIN_VALUE), decodePcm16Samples(wavAudio))
    }

    /**
     * Reads the stored WAV payload rather than duplicating its encoding policy in the assertion.
     */
    private fun decodePcm16Samples(wavAudio: ByteArray): ShortArray
    {
        val samples = ShortArray((wavAudio.size - 44) / 2)
        val buffer = ByteBuffer.wrap(wavAudio).order(ByteOrder.LITTLE_ENDIAN)
        for (sampleIndex in samples.indices)
        {
            samples[sampleIndex] = buffer.getShort(44 + (sampleIndex * 2))
        }
        return samples
    }
}
