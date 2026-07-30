package com.cleardictate.desktop

import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

/**
 * Identifies the external native worker and model required by the Windows developer preview.
 */
data class DesktopRuntimeConfiguration(
    val workerExecutable: Path,
    val workerLauncherExecutable: Path,
    val modelPath: Path,
    val inferenceThreadCount: Int = 4
)

/**
 * Describes whether local polishing can be started without exposing transcript content.
 */
sealed interface DesktopRuntimeReadiness
{
    data class Ready(val configuration: DesktopRuntimeConfiguration) : DesktopRuntimeReadiness

    data class Unavailable(val explanation: String) : DesktopRuntimeReadiness
}

/**
 * Resolves explicit overrides first, then the checked-out repository's developer build layout.
 */
class DesktopRuntimeConfigurationLocator(
    private val currentDirectory: Path = Path.of("").toAbsolutePath().normalize(),
    private val systemPropertyReader: (String) -> String? = System::getProperty,
    private val environmentVariableReader: (String) -> String? = System::getenv,
    private val isRegularFile: (Path) -> Boolean = Files::isRegularFile
)
{
    fun locate(): DesktopRuntimeReadiness
    {
        return try
        {
            val workerExecutable = resolvePath(
                systemPropertyName = WORKER_EXECUTABLE_PROPERTY,
                environmentVariableName = WORKER_EXECUTABLE_ENVIRONMENT_VARIABLE,
                repositoryRelativeDefault = "native-worker/build-llama/Debug/clear_dictate_worker.exe"
            )
            val workerLauncherExecutable = workerExecutable.resolveSibling("clear_dictate_worker_launcher.exe")
            val modelPath = resolvePath(
                systemPropertyName = TEXT_MODEL_PROPERTY,
                environmentVariableName = TEXT_MODEL_ENVIRONMENT_VARIABLE,
                repositoryRelativeDefault = ".tooling/models/qwen2.5-0.5b-instruct/qwen2.5-0.5b-instruct-q4_k_m.gguf"
            )

            if (isRegularFile(workerExecutable) &&
                isRegularFile(workerLauncherExecutable) &&
                isRegularFile(modelPath))
            {
                DesktopRuntimeReadiness.Ready(
                    DesktopRuntimeConfiguration(
                        workerExecutable = workerExecutable,
                        workerLauncherExecutable = workerLauncherExecutable,
                        modelPath = modelPath
                    )
                )
            }
            else
            {
                DesktopRuntimeReadiness.Unavailable(MISSING_RUNTIME_EXPLANATION)
            }
        }
        catch (_: InvalidPathException)
        {
            DesktopRuntimeReadiness.Unavailable(MISSING_RUNTIME_EXPLANATION)
        }
    }

    private fun resolvePath(systemPropertyName: String, environmentVariableName: String, repositoryRelativeDefault: String): Path
    {
        val configuredPath = systemPropertyReader(systemPropertyName)
            ?.takeIf(String::isNotBlank)
            ?: environmentVariableReader(environmentVariableName)?.takeIf(String::isNotBlank)

        return if (configuredPath != null)
        {
            Path.of(configuredPath)
        }
        else
        {
            currentDirectory.resolve(repositoryRelativeDefault)
        }.toAbsolutePath().normalize()
    }

    companion object
    {
        const val WORKER_EXECUTABLE_PROPERTY = "clearDictate.workerExecutable"
        const val TEXT_MODEL_PROPERTY = "clearDictate.textModel"
        const val WORKER_EXECUTABLE_ENVIRONMENT_VARIABLE = "CLEAR_DICTATE_WORKER_EXECUTABLE"
        const val TEXT_MODEL_ENVIRONMENT_VARIABLE = "CLEAR_DICTATE_TEXT_MODEL"

        private const val MISSING_RUNTIME_EXPLANATION =
            "Polished mode needs the local Debug worker and Qwen model. Raw and Clean modes remain available."
    }
}
