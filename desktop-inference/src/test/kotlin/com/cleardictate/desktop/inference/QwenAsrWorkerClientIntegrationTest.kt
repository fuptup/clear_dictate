package com.cleardictate.desktop.inference

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Path
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Exercises the Kotlin binary-pipe client against the real pinned CUDA ASR worker and public spoken fixture.
 */
class QwenAsrWorkerClientIntegrationTest
{
    @Test
    fun `Kotlin client transcribes the spoken fixture through the persistent Python worker`() = runBlocking {
        val wslExecutable = System.getProperty(WSL_EXECUTABLE_PROPERTY)
        val wslDistribution = System.getProperty(WSL_DISTRIBUTION_PROPERTY)
        val workerScript = System.getProperty(WORKER_SCRIPT_PROPERTY)
        val modelLock = System.getProperty(MODEL_LOCK_PROPERTY)
        val waveFixture = System.getProperty(WAVE_FIXTURE_PROPERTY)
        assumeTrue(
            listOf(wslExecutable, wslDistribution, workerScript, modelLock, waveFixture).all { it != null },
            "The Qwen3-ASR runtime and fixture properties are required for this integration test."
        )

        QwenAsrWorkerClient.start(
            QwenAsrWorkerConfiguration(
                wslExecutable = Path.of(requireNotNull(wslExecutable)),
                wslDistribution = requireNotNull(wslDistribution),
                workerScript = Path.of(requireNotNull(workerScript)),
                modelLock = Path.of(requireNotNull(modelLock))
            )
        ).use { client ->
            val audio = loadPcm16Wave(Path.of(requireNotNull(waveFixture)))
            client.warmUp()
            val session = client.startSession()
            val midpoint = audio.samples.size / 2
            session.accept(CapturedAudio(audio.sampleRate, audio.samples.copyOfRange(0, midpoint)))
            session.accept(CapturedAudio(audio.sampleRate, audio.samples.copyOfRange(midpoint, audio.samples.size)))
            val transcript = session.finish()

            assertTrue(transcript.transcript.isNotBlank())
            assertTrue(transcript.processingMilliseconds > 0)
        }
    }

    /**
     * Decodes the exact mono PCM fixture format without introducing another audio dependency.
     */
    private fun loadPcm16Wave(wavePath: Path): CapturedAudio
    {
        return AudioSystem.getAudioInputStream(wavePath.toFile()).use { audioStream ->
            val format = audioStream.format
            assertEquals(AudioFormat.Encoding.PCM_SIGNED, format.encoding)
            assertEquals(1, format.channels)
            assertEquals(16, format.sampleSizeInBits)
            assertEquals(false, format.isBigEndian)
            val pcmBytes = audioStream.readAllBytes()
            val samples = FloatArray(pcmBytes.size / Short.SIZE_BYTES)
            val sampleBuffer = ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN)
            for (sampleIndex in samples.indices)
            {
                samples[sampleIndex] = sampleBuffer.short / 32768.0F
            }
            pcmBytes.fill(0)
            CapturedAudio(format.sampleRate.toInt(), samples)
        }
    }

    private companion object
    {
        const val WSL_EXECUTABLE_PROPERTY = "clearDictate.wslExecutable"
        const val WSL_DISTRIBUTION_PROPERTY = "clearDictate.wslDistribution"
        const val WORKER_SCRIPT_PROPERTY = "clearDictate.asrWorkerScript"
        const val MODEL_LOCK_PROPERTY = "clearDictate.asrModelLock"
        const val WAVE_FIXTURE_PROPERTY = "clearDictate.asrWaveFixture"
    }
}
