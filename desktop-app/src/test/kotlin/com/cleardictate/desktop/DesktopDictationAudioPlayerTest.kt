package com.cleardictate.desktop

import com.cleardictate.desktop.inference.CapturedAudio
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * Verifies that row playback retrieves only the selected record and releases its output resource with the window.
 */
class DesktopDictationAudioPlayerTest
{
    @Test
    fun `plays selected history WAV and closes output`() = runTest {
        val history = SqliteDesktopDictationHistory.open(Files.createTempDirectory("cleardictate-playback").resolve("history.sqlite"))
        history.record(
            Instant.parse("2026-08-10T20:00:00Z"),
            CapturedAudio(16_000, floatArrayOf(0.25F)),
            DesktopDictationResult("raw", "polished", DesktopDictationTiming(0, 1, 2, 3))
        )
        var playedAudio = byteArrayOf()
        var closed = false
        val output = object : WavAudioOutput
        {
            override fun play(wavAudio: ByteArray)
            {
                playedAudio = wavAudio.copyOf()
            }

            override fun close()
            {
                closed = true
            }
        }
        val player = DesktopDictationAudioPlayer(history, output)

        player.play(history.readSummaries().single().identifier)
        player.close()

        assertContentEquals("RIFF".encodeToByteArray(), playedAudio.copyOfRange(0, 4))
        assertEquals(true, closed)
    }
}
