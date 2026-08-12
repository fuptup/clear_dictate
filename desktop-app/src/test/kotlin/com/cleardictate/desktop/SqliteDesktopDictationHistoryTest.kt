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
import kotlin.test.assertFailsWith

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
        assertEquals(null, entry.correctedTranscript)
        assertEquals(null, entry.correctedAt)
        assertEquals(0, entry.audioDurationMilliseconds)
        assertEquals(result.timing, entry.timing)
        assertContentEquals("RIFF".encodeToByteArray(), wavAudio.copyOfRange(0, 4))
        assertContentEquals("WAVE".encodeToByteArray(), wavAudio.copyOfRange(8, 12))
        assertEquals(16_000, ByteBuffer.wrap(wavAudio).order(ByteOrder.LITTLE_ENDIAN).getInt(24))
        assertContentEquals(shortArrayOf(0, 16_384, Short.MIN_VALUE), decodePcm16Samples(wavAudio))
    }

    @Test
    fun `summary derives sampled audio duration from the retained WAV header`() = runTest {
        val history = SqliteDesktopDictationHistory.open(Files.createTempDirectory("cleardictate-duration").resolve("history.sqlite"))
        val capturedAudio = CapturedAudio(sampleRate = 16_000, samples = FloatArray(24_000))
        val result = DesktopDictationResult("raw", "polished", DesktopDictationTiming(0, 1, 2, 3))

        history.record(Instant.parse("2026-08-12T12:00:00Z"), capturedAudio, result)

        assertEquals(1_500, history.readSummaries().single().audioDurationMilliseconds)
    }

    @Test
    fun `stores and updates a reviewed correction without replacing model output`() = runTest {
        val databasePath = Files.createTempDirectory("cleardictate-correction").resolve("history.sqlite")
        val history = SqliteDesktopDictationHistory.open(databasePath)
        val result = DesktopDictationResult("raw model output", "polished model output", DesktopDictationTiming(0, 1, 2, 3))
        history.record(Instant.parse("2026-08-11T10:00:00Z"), CapturedAudio(16_000, floatArrayOf(0.0F)), result)
        val identifier = history.readSummaries().single().identifier

        history.saveCorrection(identifier, " First reviewed target. ", Instant.parse("2026-08-11T10:01:00Z"))
        history.saveCorrection(identifier, "Final reviewed target.", Instant.parse("2026-08-11T10:02:00Z"))
        val correctedEntry = history.readSummaries().single()

        assertEquals(result.rawTranscript, correctedEntry.rawTranscript)
        assertEquals(result.polishedTranscript, correctedEntry.polishedTranscript)
        assertEquals("Final reviewed target.", correctedEntry.correctedTranscript)
        assertEquals(Instant.parse("2026-08-11T10:02:00Z"), correctedEntry.correctedAt)
    }

    @Test
    fun `correction cannot be stored for a missing history record`() = runTest {
        val databasePath = Files.createTempDirectory("cleardictate-missing-correction").resolve("history.sqlite")
        val history = SqliteDesktopDictationHistory.open(databasePath)

        assertFailsWith<IllegalStateException> {
            history.saveCorrection(999, "There is no matching dictation.")
        }
        assertEquals(emptyList(), history.readSummaries())
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
