package com.cleardictate.desktop

import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

/**
 * Identifies every local executable, script, and model required by push-to-talk dictation.
 */
data class DesktopRuntimeConfiguration(
    val textWorkerExecutable: Path,
    val audioCaptureWorkerExecutable: Path,
    val audioDeviceEnumeratorExecutable: Path,
    val workerLauncherExecutable: Path,
    val pythonExecutable: Path,
    val asrWorkerScript: Path,
    val asrModelLock: Path,
    val textModelPath: Path,
    val asrModelDirectory: Path,
    val inferenceThreadCount: Int = 8
)

/**
 * Describes whether the complete local dictation pipeline can start.
 */
sealed interface DesktopRuntimeReadiness
{
    data class Ready(val configuration: DesktopRuntimeConfiguration) : DesktopRuntimeReadiness
    data class Unavailable(val explanation: String) : DesktopRuntimeReadiness
}

/**
 * Resolves explicit overrides first, then the checked-out repository's Debug runtime layout.
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
        val packagedApplicationDirectory = systemPropertyReader(JPACKAGE_APPLICATION_PATH_PROPERTY)?.takeIf(String::isNotBlank)?.let(Path::of)?.parent
        sequenceOf(packagedApplicationDirectory, currentDirectory)
            .filterNotNull()
            .flatMap { startingDirectory -> generateSequence(startingDirectory) { directory -> directory.parent } }
            .firstOrNull { candidate -> isRegularFile(candidate.resolve(REPOSITORY_SENTINEL_FILENAME)) }
            ?: currentDirectory
    }

    fun locate(): DesktopRuntimeReadiness
    {
        return try
        {
            val textWorkerExecutable = resolvePath(TEXT_WORKER_PROPERTY, TEXT_WORKER_ENVIRONMENT_VARIABLE, "native-worker/build-llama/Debug/clear_dictate_worker.exe")
            val audioCaptureWorkerExecutable = resolvePath(AUDIO_CAPTURE_WORKER_PROPERTY, AUDIO_CAPTURE_WORKER_ENVIRONMENT_VARIABLE, "native-worker/build-llama/Debug/clear_dictate_audio_capture_worker.exe")
            val audioDeviceEnumeratorExecutable = resolvePath(AUDIO_DEVICE_ENUMERATOR_PROPERTY, AUDIO_DEVICE_ENUMERATOR_ENVIRONMENT_VARIABLE, "native-worker/build-llama/Debug/clear_dictate_audio_device_enumerator.exe")
            val workerLauncherExecutable = textWorkerExecutable.resolveSibling("clear_dictate_worker_launcher.exe")
            val pythonExecutable = resolvePath(PYTHON_EXECUTABLE_PROPERTY, PYTHON_EXECUTABLE_ENVIRONMENT_VARIABLE, ".tooling/qwen-python/Scripts/python.exe")
            val asrWorkerScript = resolvePath(ASR_WORKER_SCRIPT_PROPERTY, ASR_WORKER_SCRIPT_ENVIRONMENT_VARIABLE, "gpu-worker/qwen_asr_worker.py")
            val asrModelLock = resolvePath(ASR_MODEL_LOCK_PROPERTY, ASR_MODEL_LOCK_ENVIRONMENT_VARIABLE, "gpu-worker/qwen3-asr-1.7b-lock.json")
            val textModelPath = resolvePath(TEXT_MODEL_PROPERTY, TEXT_MODEL_ENVIRONMENT_VARIABLE, ".tooling/models/qwen3.5-9b/Qwen3.5-9B-Q6_K.gguf")
            val asrModelDirectory = resolvePath(ASR_MODEL_DIRECTORY_PROPERTY, ASR_MODEL_DIRECTORY_ENVIRONMENT_VARIABLE, ".tooling/models/qwen3-asr-1.7b")

            val requiredFiles = listOf(textWorkerExecutable, audioCaptureWorkerExecutable, workerLauncherExecutable, pythonExecutable, asrWorkerScript, asrModelLock, textModelPath)
            if (requiredFiles.all(isRegularFile) && isDirectory(asrModelDirectory))
            {
                DesktopRuntimeReadiness.Ready(
                    DesktopRuntimeConfiguration(
                        textWorkerExecutable,
                        audioCaptureWorkerExecutable,
                        audioDeviceEnumeratorExecutable,
                        workerLauncherExecutable,
                        pythonExecutable,
                        asrWorkerScript,
                        asrModelLock,
                        textModelPath,
                        asrModelDirectory
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
        val configuredPath = systemPropertyReader(systemPropertyName)?.takeIf(String::isNotBlank)
            ?: environmentVariableReader(environmentVariableName)?.takeIf(String::isNotBlank)
        return (configuredPath?.let(Path::of) ?: repositoryRoot.resolve(repositoryRelativeDefault)).toAbsolutePath().normalize()
    }

    companion object
    {
        const val TEXT_WORKER_PROPERTY = "clearDictate.textWorkerExecutable"
        const val AUDIO_CAPTURE_WORKER_PROPERTY = "clearDictate.audioCaptureWorkerExecutable"
        const val AUDIO_DEVICE_ENUMERATOR_PROPERTY = "clearDictate.audioDeviceEnumeratorExecutable"
        const val PYTHON_EXECUTABLE_PROPERTY = "clearDictate.pythonExecutable"
        const val ASR_WORKER_SCRIPT_PROPERTY = "clearDictate.asrWorkerScript"
        const val ASR_MODEL_LOCK_PROPERTY = "clearDictate.asrModelLock"
        const val TEXT_MODEL_PROPERTY = "clearDictate.textModel"
        const val ASR_MODEL_DIRECTORY_PROPERTY = "clearDictate.asrModelDirectory"
        const val TEXT_WORKER_ENVIRONMENT_VARIABLE = "CLEAR_DICTATE_TEXT_WORKER_EXECUTABLE"
        const val AUDIO_CAPTURE_WORKER_ENVIRONMENT_VARIABLE = "CLEAR_DICTATE_AUDIO_CAPTURE_WORKER_EXECUTABLE"
        const val AUDIO_DEVICE_ENUMERATOR_ENVIRONMENT_VARIABLE = "CLEAR_DICTATE_AUDIO_DEVICE_ENUMERATOR_EXECUTABLE"
        const val PYTHON_EXECUTABLE_ENVIRONMENT_VARIABLE = "CLEAR_DICTATE_PYTHON_EXECUTABLE"
        const val ASR_WORKER_SCRIPT_ENVIRONMENT_VARIABLE = "CLEAR_DICTATE_ASR_WORKER_SCRIPT"
        const val ASR_MODEL_LOCK_ENVIRONMENT_VARIABLE = "CLEAR_DICTATE_ASR_MODEL_LOCK"
        const val TEXT_MODEL_ENVIRONMENT_VARIABLE = "CLEAR_DICTATE_TEXT_MODEL"
        const val ASR_MODEL_DIRECTORY_ENVIRONMENT_VARIABLE = "CLEAR_DICTATE_ASR_MODEL_DIRECTORY"

        private const val MISSING_RUNTIME_EXPLANATION = "Install the local Qwen3-ASR and Qwen3.5 runtime files to enable push-to-talk dictation."
        private const val REPOSITORY_SENTINEL_FILENAME = "settings.gradle.kts"
        private const val JPACKAGE_APPLICATION_PATH_PROPERTY = "jpackage.app-path"
    }
}
