package com.cleardictate.desktop

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Verifies deterministic discovery of the two external files required by Polished mode.
 */
class DesktopRuntimeConfigurationLocatorTest
{
    @Test
    fun `explicit system properties take precedence over environment and repository defaults`()
    {
        val existingFiles = setOf(
            Path.of("C:/explicit/worker.exe"),
            Path.of("C:/explicit/clear_dictate_worker_launcher.exe"),
            Path.of("C:/explicit/model.gguf")
        )
        val locator = DesktopRuntimeConfigurationLocator(
            currentDirectory = Path.of("E:/VoiceToText"),
            systemPropertyReader = { propertyName ->
                when (propertyName)
                {
                    DesktopRuntimeConfigurationLocator.WORKER_EXECUTABLE_PROPERTY -> "C:/explicit/worker.exe"
                    DesktopRuntimeConfigurationLocator.TEXT_MODEL_PROPERTY -> "C:/explicit/model.gguf"
                    else -> null
                }
            },
            environmentVariableReader = { "C:/ignored/$it" },
            isRegularFile = existingFiles::contains
        )

        val result = assertIs<DesktopRuntimeReadiness.Ready>(locator.locate())

        assertEquals(Path.of("C:/explicit/worker.exe"), result.configuration.workerExecutable)
        assertEquals(Path.of("C:/explicit/clear_dictate_worker_launcher.exe"), result.configuration.workerLauncherExecutable)
        assertEquals(Path.of("C:/explicit/model.gguf"), result.configuration.modelPath)
    }

    @Test
    fun `repository development paths are used when no overrides are present`()
    {
        val repositoryRoot = Path.of("E:/VoiceToText")
        val workerExecutable = repositoryRoot.resolve("native-worker/build-llama/Debug/clear_dictate_worker.exe")
        val workerLauncherExecutable = repositoryRoot.resolve("native-worker/build-llama/Debug/clear_dictate_worker_launcher.exe")
        val modelPath = repositoryRoot.resolve(".tooling/models/qwen2.5-0.5b-instruct/qwen2.5-0.5b-instruct-q4_k_m.gguf")
        val locator = DesktopRuntimeConfigurationLocator(
            currentDirectory = repositoryRoot,
            systemPropertyReader = { null },
            environmentVariableReader = { null },
            isRegularFile = setOf(workerExecutable, workerLauncherExecutable, modelPath)::contains
        )

        val result = assertIs<DesktopRuntimeReadiness.Ready>(locator.locate())

        assertEquals(workerExecutable, result.configuration.workerExecutable)
        assertEquals(workerLauncherExecutable, result.configuration.workerLauncherExecutable)
        assertEquals(modelPath, result.configuration.modelPath)
    }

    @Test
    fun `missing launcher produces a transcript-free readiness explanation`()
    {
        val repositoryRoot = Path.of("E:/VoiceToText")
        val workerExecutable = repositoryRoot.resolve("native-worker/build-llama/Debug/clear_dictate_worker.exe")
        val modelPath = repositoryRoot.resolve(".tooling/models/qwen2.5-0.5b-instruct/qwen2.5-0.5b-instruct-q4_k_m.gguf")
        val locator = DesktopRuntimeConfigurationLocator(
            currentDirectory = repositoryRoot,
            systemPropertyReader = { null },
            environmentVariableReader = { null },
            isRegularFile = setOf(workerExecutable, modelPath)::contains
        )

        val result = assertIs<DesktopRuntimeReadiness.Unavailable>(locator.locate())

        assertEquals(
            "Polished mode needs the local Debug worker and Qwen model. Raw and Clean modes remain available.",
            result.explanation
        )
    }
}
