package com.cleardictate.desktop.inference

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Path
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

/**
 * Exercises the Kotlin binary-pipe client against the real pinned CUDA ASR worker and public spoken fixture.
 */
class QwenAsrWorkerClientIntegrationTest
{
    @Test
    fun `Kotlin client transcribes the spoken fixture through the persistent Python worker`() = runBlocking {
        val pythonExecutable = System.getProperty(PYTHON_EXECUTABLE_PROPERTY)
        val workerScript = System.getProperty(WORKER_SCRIPT_PROPERTY)
        val modelDirectory = System.getProperty(MODEL_DIRECTORY_PROPERTY)
        val modelLock = System.getProperty(MODEL_LOCK_PROPERTY)
        val waveFixture = System.getProperty(WAVE_FIXTURE_PROPERTY)
        assumeTrue(
            listOf(pythonExecutable, workerScript, modelDirectory, modelLock, waveFixture).all { it != null },
            "The Qwen3-ASR runtime and fixture properties are required for this integration test."
        )

        QwenAsrWorkerClient.start(
            QwenAsrWorkerConfiguration(
                Path.of(requireNotNull(pythonExecutable)),
                Path.of(requireNotNull(workerScript)),
                Path.of(requireNotNull(modelDirectory)),
                Path.of(requireNotNull(modelLock))
            )
        ).use { client ->
            val transcript = client.transcribe(loadPcm16Wave(Path.of(requireNotNull(waveFixture))))

            assertContains(transcript.lowercase(), "best of times")
            assertContains(transcript.lowercase(), "worst of times")
            assertContains(transcript.lowercase(), "age of wisdom")
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
        const val PYTHON_EXECUTABLE_PROPERTY = "clearDictate.pythonExecutable"
        const val WORKER_SCRIPT_PROPERTY = "clearDictate.asrWorkerScript"
        const val MODEL_DIRECTORY_PROPERTY = "clearDictate.asrModelDirectory"
        const val MODEL_LOCK_PROPERTY = "clearDictate.asrModelLock"
        const val WAVE_FIXTURE_PROPERTY = "clearDictate.asrWaveFixture"
    }
}
