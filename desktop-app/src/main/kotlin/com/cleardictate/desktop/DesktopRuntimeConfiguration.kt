package com.cleardictate.desktop

import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

/**
 * Identifies the external native worker and model required by the Windows developer preview.
 */
data class DesktopRuntimeConfiguration(
    val workerExecutable: Path,
    val speechWorkerExecutable: Path,
    val audioDeviceEnumeratorExecutable: Path,
    val workerLauncherExecutable: Path,
    val modelPath: Path,
    val speechModelDirectory: Path,
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
    private val isRegularFile: (Path) -> Boolean = Files::isRegularFile,
    private val isDirectory: (Path) -> Boolean = Files::isDirectory
)
{
    private val repositoryRoot: Path by lazy {
        generateSequence(currentDirectory) { directory -> directory.parent }
            .firstOrNull { candidate -> isRegularFile(candidate.resolve(REPOSITORY_SENTINEL_FILENAME)) }
            ?: currentDirectory
    }

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
            val speechWorkerExecutable = resolvePath(
                systemPropertyName = SPEECH_WORKER_EXECUTABLE_PROPERTY,
                environmentVariableName = SPEECH_WORKER_EXECUTABLE_ENVIRONMENT_VARIABLE,
                repositoryRelativeDefault = "native-worker/build-llama/Debug/clear_dictate_speech_worker.exe"
            )
            val audioDeviceEnumeratorExecutable = resolvePath(
                systemPropertyName = AUDIO_DEVICE_ENUMERATOR_EXECUTABLE_PROPERTY,
                environmentVariableName = AUDIO_DEVICE_ENUMERATOR_EXECUTABLE_ENVIRONMENT_VARIABLE,
                repositoryRelativeDefault = "native-worker/build-llama/Debug/clear_dictate_audio_device_enumerator.exe"
            )
            val modelPath = resolvePath(
                systemPropertyName = TEXT_MODEL_PROPERTY,
                environmentVariableName = TEXT_MODEL_ENVIRONMENT_VARIABLE,
                repositoryRelativeDefault = ".tooling/models/qwen2.5-0.5b-instruct/qwen2.5-0.5b-instruct-q4_k_m.gguf"
            )
            val speechModelDirectory = resolvePath(
                systemPropertyName = SPEECH_MODEL_DIRECTORY_PROPERTY,
                environmentVariableName = SPEECH_MODEL_DIRECTORY_ENVIRONMENT_VARIABLE,
                repositoryRelativeDefault = ".tooling/models/moonshine-tiny-streaming-en"
            )

            if (isRegularFile(workerExecutable) &&
                isRegularFile(speechWorkerExecutable) &&
                isRegularFile(workerLauncherExecutable) &&
                isRegularFile(modelPath) &&
                isDirectory(speechModelDirectory))
            {
                DesktopRuntimeReadiness.Ready(
                    DesktopRuntimeConfiguration(
                        workerExecutable = workerExecutable,
                        speechWorkerExecutable = speechWorkerExecutable,
                        audioDeviceEnumeratorExecutable = audioDeviceEnumeratorExecutable,
                        workerLauncherExecutable = workerLauncherExecutable,
                        modelPath = modelPath,
                        speechModelDirectory = speechModelDirectory
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
            repositoryRoot.resolve(repositoryRelativeDefault)
        }.toAbsolutePath().normalize()
    }

    companion object
    {
        const val WORKER_EXECUTABLE_PROPERTY = "clearDictate.workerExecutable"
        const val TEXT_MODEL_PROPERTY = "clearDictate.textModel"
        const val SPEECH_WORKER_EXECUTABLE_PROPERTY = "clearDictate.speechWorkerExecutable"
        const val AUDIO_DEVICE_ENUMERATOR_EXECUTABLE_PROPERTY = "clearDictate.audioDeviceEnumeratorExecutable"
        const val SPEECH_MODEL_DIRECTORY_PROPERTY = "clearDictate.speechModelDirectory"
        const val WORKER_EXECUTABLE_ENVIRONMENT_VARIABLE = "CLEAR_DICTATE_WORKER_EXECUTABLE"
        const val TEXT_MODEL_ENVIRONMENT_VARIABLE = "CLEAR_DICTATE_TEXT_MODEL"
        const val SPEECH_WORKER_EXECUTABLE_ENVIRONMENT_VARIABLE = "CLEAR_DICTATE_SPEECH_WORKER_EXECUTABLE"
        const val AUDIO_DEVICE_ENUMERATOR_EXECUTABLE_ENVIRONMENT_VARIABLE = "CLEAR_DICTATE_AUDIO_DEVICE_ENUMERATOR_EXECUTABLE"
        const val SPEECH_MODEL_DIRECTORY_ENVIRONMENT_VARIABLE = "CLEAR_DICTATE_SPEECH_MODEL_DIRECTORY"

        private const val MISSING_RUNTIME_EXPLANATION =
            "Recording and Polished mode need the local Debug workers and pinned models. Text-only Raw and Clean modes remain available."
        private const val REPOSITORY_SENTINEL_FILENAME = "settings.gradle.kts"
    }
}
