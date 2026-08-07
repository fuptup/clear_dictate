@echo off
setlocal

set "CLEAR_DICTATE_JAVA_HOME=C:\Program Files\Unity\Hub\Editor\6000.4.8f1\Editor\Data\PlaybackEngines\AndroidPlayer\OpenJDK"
set "CLEAR_DICTATE_ANDROID_HOME=E:\VoiceToText\.tooling\android-sdk"

if not exist "%CLEAR_DICTATE_JAVA_HOME%\bin\java.exe" (
    echo ClearDictate could not find the configured Java 17 development runtime.
    echo Update CLEAR_DICTATE_JAVA_HOME inside this launcher before retrying.
    pause
    exit /b 1
)

if not exist "%~dp0native-worker\build-llama\Debug\clear_dictate_worker.exe" (
    echo ClearDictate could not find the Debug native worker.
    echo Follow docs\WINDOWS_DEVELOPMENT.md to build it before retrying.
    pause
    exit /b 1
)

if not exist "%~dp0native-worker\build-llama\Debug\clear_dictate_audio_capture_worker.exe" (
    echo ClearDictate could not find the Debug native audio capture worker.
    echo Follow docs\WINDOWS_DEVELOPMENT.md to build it before retrying.
    pause
    exit /b 1
)

if not exist "%~dp0native-worker\build-llama\Debug\clear_dictate_worker_launcher.exe" (
    echo ClearDictate could not find the Debug worker launcher.
    echo Follow docs\WINDOWS_DEVELOPMENT.md to build all native targets before retrying.
    pause
    exit /b 1
)

if not exist "%~dp0.tooling\models\qwen3.5-9b\Qwen3.5-9B-Q6_K.gguf" (
    echo ClearDictate could not find the configured local Qwen3.5 model.
    echo Follow docs\WINDOWS_DEVELOPMENT.md to install it before retrying.
    pause
    exit /b 1
)

if not exist "%~dp0.tooling\models\qwen3-asr-1.7b\model.safetensors" (
    echo ClearDictate could not find the configured local Qwen3-ASR model.
    echo Follow docs\WINDOWS_DEVELOPMENT.md to install it before retrying.
    pause
    exit /b 1
)

if not exist "%~dp0.tooling\qwen-python\Scripts\python.exe" (
    echo ClearDictate could not find the local Qwen3-ASR Python runtime.
    echo Follow docs\WINDOWS_DEVELOPMENT.md to install it before retrying.
    pause
    exit /b 1
)

set "JAVA_HOME=%CLEAR_DICTATE_JAVA_HOME%"
set "ANDROID_HOME=%CLEAR_DICTATE_ANDROID_HOME%"
set "Path=%JAVA_HOME%\bin;%Path%"

pushd "%~dp0"
call gradlew.bat :desktop-app:run
set "CLEAR_DICTATE_EXIT_CODE=%ERRORLEVEL%"
popd

if not "%CLEAR_DICTATE_EXIT_CODE%"=="0" (
    echo.
    echo ClearDictate stopped with development error code %CLEAR_DICTATE_EXIT_CODE%.
    pause
)

exit /b %CLEAR_DICTATE_EXIT_CODE%
