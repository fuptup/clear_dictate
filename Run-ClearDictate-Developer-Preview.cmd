@echo off
setlocal

set "CLEAR_DICTATE_APP=%~dp0desktop-app\build\compose\binaries\main\app\ClearDictate\ClearDictate.exe"
set "CLEAR_DICTATE_TEXT_WORKER_EXECUTABLE=%~dp0native-worker\build-llama\Debug\clear_dictate_worker.exe"
set "CLEAR_DICTATE_AUDIO_CAPTURE_WORKER_EXECUTABLE=%~dp0native-worker\build-llama\Debug\clear_dictate_audio_capture_worker.exe"
set "CLEAR_DICTATE_AUDIO_DEVICE_ENUMERATOR_EXECUTABLE=%~dp0native-worker\build-llama\Debug\clear_dictate_audio_device_enumerator.exe"
set "CLEAR_DICTATE_PYTHON_EXECUTABLE=%~dp0.tooling\qwen-python\Scripts\python.exe"
set "CLEAR_DICTATE_ASR_WORKER_SCRIPT=%~dp0gpu-worker\qwen_asr_worker.py"
set "CLEAR_DICTATE_ASR_MODEL_LOCK=%~dp0gpu-worker\qwen3-asr-1.7b-lock.json"
set "CLEAR_DICTATE_TEXT_MODEL=%~dp0.tooling\models\qwen3.5-9b\Qwen3.5-9B-Q6_K.gguf"
set "CLEAR_DICTATE_ASR_MODEL_DIRECTORY=%~dp0.tooling\models\qwen3-asr-1.7b"

if not exist "%CLEAR_DICTATE_APP%" (
    echo ClearDictate could not find the built Windows application image.
    echo Follow docs\WINDOWS_DEVELOPMENT.md to create it before retrying.
    pause
    exit /b 1
)

pushd "%~dp0"
start "" "%CLEAR_DICTATE_APP%"
set "CLEAR_DICTATE_EXIT_CODE=%ERRORLEVEL%"
popd

exit /b %CLEAR_DICTATE_EXIT_CODE%
