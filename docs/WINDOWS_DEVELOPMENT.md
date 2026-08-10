# Windows development

## Implemented flow

The Windows preview is an offline push-to-talk application. Holding the control records 16 kHz mono audio through the selected Windows capture endpoint. Releasing
it stops capture, sends the completed in-memory recording to Qwen3-ASR 1.7B on CUDA, then sends the raw transcript to Qwen3.5 9B Q6_K for local polishing. Only the
polished text is selected for copying or editing.

Recognition never runs while the button is held. After a successful result, ClearDictate stores the audio as a mono PCM16 WAV BLOB together with both transcripts,
UTC capture datetime, and queue/ASR/rewrite/total durations in `%LOCALAPPDATA%\ClearDictate\dictation-history.sqlite`; the in-memory capture samples are then
overwritten. The native capture worker, Python ASR worker, and llama.cpp text worker communicate through private pipes. Process isolation is a crash and ownership
boundary, not a hostile-code sandbox.

The system-wide shortcut, focused-application insertion, history browsing/export, installer, and device-compatibility matrix remain separate product work.

## Locked inputs

- llama.cpp release `b10189`, commit `b2f221684fcd898e947a121baeda80f345da3e6b`
- Qwen3-ASR repository `Qwen/Qwen3-ASR-1.7B-hf`, revision `bcd2b5b7f32b480ab5790554cfa8347f246a14f3`
- Qwen3.5 GGUF repository `unsloth/Qwen3.5-9B-GGUF`, revision `3885219b6810b007914f3a7950a8d1b469d598a5`
- Qwen3.5 file `Qwen3.5-9B-Q6_K.gguf`, 7,458,301,152 bytes, SHA-256 `91898433cf5ce0a8f45516a4cc3e9343b6e01d052d01f684309098c66a326c59`
- Transformers `5.13.0` and Accelerate `1.10.1`

The native text worker verifies the GGUF length and digest before loading the same open file handle. The Python ASR worker verifies every required model file against `gpu-worker/qwen3-asr-1.7b-lock.json` before Transformers parses it.
The text worker also applies Qwen3.5's non-thinking generation prefix so the fixed 256-token response budget is spent on the polished transcript rather than hidden reasoning.

## Local setup

Run these commands from the repository root:

```powershell
C:\Python313\python.exe -m venv --system-site-packages .tooling\qwen-python
.tooling\qwen-python\Scripts\python.exe -m pip install transformers==5.13.0 accelerate==1.10.1
.tooling\qwen-python\Scripts\hf.exe download Qwen/Qwen3-ASR-1.7B-hf --revision bcd2b5b7f32b480ab5790554cfa8347f246a14f3 --local-dir .tooling\models\qwen3-asr-1.7b
.tooling\qwen-python\Scripts\hf.exe download unsloth/Qwen3.5-9B-GGUF Qwen3.5-9B-Q6_K.gguf --revision 3885219b6810b007914f3a7950a8d1b469d598a5 --local-dir .tooling\models\qwen3.5-9b
git clone --filter=blob:none --no-checkout https://github.com/ggml-org/llama.cpp.git .tooling\upstream\llama.cpp-cleardictate-cuda
git -C .tooling\upstream\llama.cpp-cleardictate-cuda checkout b2f221684fcd898e947a121baeda80f345da3e6b
```

The Python environment intentionally reuses the installed CUDA PyTorch build. Confirm that `torch.cuda.is_available()` is true before continuing.

## Native Debug build

The project targets CUDA architecture 8.6 for the RTX 3090. Configure the Visual Studio Debug build:

```powershell
cmake -S native-worker -B native-worker/build-llama `
    -DCLEAR_DICTATE_ENABLE_LLAMA=ON `
    -DLLAMA_CPP_SOURCE_DIR=E:/VoiceToText/.tooling/upstream/llama.cpp-cleardictate-cuda `
    -DCLEAR_DICTATE_ENABLE_MODEL_INTEGRATION_TESTS=ON `
    -DCLEAR_DICTATE_TEST_TEXT_MODEL=E:/VoiceToText/.tooling/models/qwen3.5-9b/Qwen3.5-9B-Q6_K.gguf
```

On this machine, build through a normalized Visual Studio environment. Serial MSBuild avoids a CUDA build-customization defect that otherwise leaves detached build nodes:

```powershell
$buildCommand = 'set "SAVED_BUILD_PATH=!PATH!" && set "Path=" && set "PATH=!SAVED_BUILD_PATH!" && ' +
    'call "C:\Program Files\Microsoft Visual Studio\2022\Community\Common7\Tools\VsDevCmd.bat" -arch=x64 -host_arch=x64 && ' +
    '"C:\Program Files\Microsoft Visual Studio\2022\Community\MSBuild\Current\Bin\MSBuild.exe" ' +
    'native-worker\build-llama\ALL_BUILD.vcxproj /p:Configuration=Debug /p:Platform=x64 /m:1 /nodeReuse:false'
cmd.exe /d /v:on /c $buildCommand
ctest --test-dir native-worker/build-llama -C Debug --output-on-failure
```

## Model and Kotlin verification

```powershell
.tooling\qwen-python\Scripts\python.exe gpu-worker\verify_qwen_asr.py .tooling\models\qwen3-asr-1.7b gpu-worker\qwen3-asr-1.7b-lock.json path\to\mono-16k-pcm16.wav
$env:JAVA_HOME='C:\Program Files\Unity\Hub\Editor\6000.4.8f1\Editor\Data\PlaybackEngines\AndroidPlayer\OpenJDK'
.\gradlew.bat :desktop-inference:test :desktop-app:test
$env:GRADLE_OPTS='-DclearDictate.workerExecutable=E:/VoiceToText/native-worker/build-llama/Debug/clear_dictate_worker.exe -DclearDictate.textModel=E:/VoiceToText/.tooling/models/qwen3.5-9b/Qwen3.5-9B-Q6_K.gguf'
.\gradlew.bat :desktop-inference:realWorkerIntegrationTest --rerun-tasks
```

The ASR fixture check prints the recognized public fixture text. The desktop application never prints microphone transcripts; successful dictations are retained in the
local SQLite history database described above.

## Run the preview

Double-click `Run-ClearDictate-Developer-Preview.cmd` or run:

```powershell
.\gradlew.bat :desktop-app:run
```

Wait for **Ready**, select a microphone, hold **Hold to talk**, speak, and release. ClearDictate loads both persistent model workers during startup so the first dictation does not also pay their loading cost.

## Developer phone endpoint

After both models reach **Ready**, ClearDictate listens on port `8765` for the versioned completed-audio protocol. Open **Phone**, choose the address on the phone's
Wi-Fi network when several are available, and scan its QR code from the Android application. The address and persistent bearer token remain selectable as a manual
fallback. The service accepts authenticated 16 kHz mono PCM16 recordings at
`/v1/dictation`, runs the same serialized Qwen3-ASR and Qwen3.5 pipeline used by desktop capture, and returns only the polished UTF-8 transcript.

The Android Debug recorder and keyboard use this endpoint after QR or manual pairing. The transport is authenticated but not encrypted, so use it only on a trusted
private network. Certificate-based pairing remains required before production use.
