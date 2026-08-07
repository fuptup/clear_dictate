package com.cleardictate.desktop

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Verifies deterministic discovery of the complete local Qwen runtime.
 */
class DesktopRuntimeConfigurationLocatorTest
{
    @Test
    fun `repository defaults identify Qwen capture transcription and polishing files`()
    {
        val root = Path.of("E:/VoiceToText")
        val expected = expectedPaths(root)
        val locator = DesktopRuntimeConfigurationLocator(
            currentDirectory = root.resolve("desktop-app"),
            systemPropertyReader = { null },
            environmentVariableReader = { null },
            isRegularFile = { path -> path.fileName.toString() != "settings.gradle.kts" || path.parent == root },
            isDirectory = { true }
        )

        val configuration = assertIs<DesktopRuntimeReadiness.Ready>(locator.locate()).configuration

        assertEquals(expected.textWorker, configuration.textWorkerExecutable)
        assertEquals(expected.audioCaptureWorker, configuration.audioCaptureWorkerExecutable)
        assertEquals(expected.python, configuration.pythonExecutable)
        assertEquals(expected.textModel, configuration.textModelPath)
        assertEquals(expected.asrModelDirectory, configuration.asrModelDirectory)
    }

    @Test
    fun `missing ASR model keeps the complete pipeline unavailable`()
    {
        val root = Path.of("E:/VoiceToText")
        val expected = expectedPaths(root)
        val locator = DesktopRuntimeConfigurationLocator(
            currentDirectory = root,
            systemPropertyReader = { null },
            environmentVariableReader = { null },
            isRegularFile = (expected.files + root.resolve("settings.gradle.kts"))::contains,
            isDirectory = { false }
        )

        val result = assertIs<DesktopRuntimeReadiness.Unavailable>(locator.locate())

        assertEquals("Install the local Qwen3-ASR and Qwen3.5 runtime files to enable push-to-talk dictation.", result.explanation)
    }

    private fun expectedPaths(root: Path): ExpectedPaths
    {
        return ExpectedPaths(
            textWorker = root.resolve("native-worker/build-llama/Debug/clear_dictate_worker.exe"),
            audioCaptureWorker = root.resolve("native-worker/build-llama/Debug/clear_dictate_audio_capture_worker.exe"),
            enumerator = root.resolve("native-worker/build-llama/Debug/clear_dictate_audio_device_enumerator.exe"),
            launcher = root.resolve("native-worker/build-llama/Debug/clear_dictate_worker_launcher.exe"),
            python = root.resolve(".tooling/qwen-python/Scripts/python.exe"),
            asrScript = root.resolve("gpu-worker/qwen_asr_worker.py"),
            asrLock = root.resolve("gpu-worker/qwen3-asr-1.7b-lock.json"),
            textModel = root.resolve(".tooling/models/qwen3.5-9b/Qwen3.5-9B-Q6_K.gguf"),
            asrModelDirectory = root.resolve(".tooling/models/qwen3-asr-1.7b")
        )
    }

    private data class ExpectedPaths(
        val textWorker: Path,
        val audioCaptureWorker: Path,
        val enumerator: Path,
        val launcher: Path,
        val python: Path,
        val asrScript: Path,
        val asrLock: Path,
        val textModel: Path,
        val asrModelDirectory: Path
    )
    {
        val files = setOf(textWorker, audioCaptureWorker, enumerator, launcher, python, asrScript, asrLock, textModel)
    }
}
