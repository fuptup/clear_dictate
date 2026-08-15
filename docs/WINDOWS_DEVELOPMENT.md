# Windows development

## Implemented flow

The Windows preview is an offline push-to-talk application. Holding the control records 16 kHz mono audio through the selected Windows capture endpoint. Releasing
it stops capture, sends the completed in-memory recording to Qwen3-ASR 1.7B on CUDA, then sends the raw transcript to Qwen3.5 9B Q6_K for local polishing. Only the
polished text is selected for copying or editing.

Recognition never runs while the button is held. After a successful result, ClearDictate stores the audio as a mono PCM16 WAV BLOB together with both transcripts,
UTC capture datetime, queue/ASR/rewrite/total durations, and whether the selected text came from Qwen or deterministic fallback in
`%LOCALAPPDATA%\ClearDictate\dictation-history.sqlite`; the in-memory capture samples are then overwritten. The native capture worker, Python ASR worker, and
llama.cpp text worker communicate through private pipes. The Kotlin domain owns the authoritative rewrite prompt and transports its system and user roles separately
to the native worker. Process isolation is a crash and ownership boundary, not a hostile-code sandbox.

The system-wide shortcut, focused-application insertion, history export, installer, and device-compatibility matrix remain separate product work.

## Locked inputs

- llama.cpp release `b10189`, commit `b2f221684fcd898e947a121baeda80f345da3e6b`
- Qwen3-ASR repository `Qwen/Qwen3-ASR-1.7B-hf`, revision `bcd2b5b7f32b480ab5790554cfa8347f246a14f3`
- Qwen3.5 GGUF repository `unsloth/Qwen3.5-9B-GGUF`, revision `3885219b6810b007914f3a7950a8d1b469d598a5`
- Qwen3.5 file `Qwen3.5-9B-Q6_K.gguf`, 7,458,301,152 bytes, SHA-256 `91898433cf5ce0a8f45516a4cc3e9343b6e01d052d01f684309098c66a326c59`
- Transformers `5.13.0` and Accelerate `1.10.1`

The native text worker verifies the GGUF length and digest before loading the same open file handle. The Python ASR worker verifies every required model file against `gpu-worker/qwen3-asr-1.7b-lock.json` before Transformers parses it.
The text worker also applies Qwen3.5's non-thinking generation prefix so the fixed 256-token response budget is spent on the polished transcript rather than hidden reasoning.

Both models require CUDA. Qwen3.5 is read through bounded staging buffers after verification, including its token embedding, so the complete model remains on the NVIDIA GPU without a retained GGUF memory mapping in system RAM. Qwen3-ASR rejects any CPU-placed parameter and returns inactive Python loading and inference pages to Windows after warm-up and each completed transcription.

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

Create the direct Windows application image with a full Java 17 JDK that includes `jmods`:

```powershell
$env:JAVA_HOME='path\to\full-jdk-17'
.\gradlew.bat :desktop-app:createDistributable --no-daemon
```

Double-click `Run-ClearDictate-Developer-Preview.cmd`. The launcher starts the generated `ClearDictate.exe` directly; Gradle and its build JVMs do not remain in memory while the app is running.

Wait for **Ready**, select a microphone, hold **Hold to talk**, speak, and release. ClearDictate loads both persistent model workers during startup so the first dictation does not also pay their loading cost.

Spoken formatting commands are converted before AI rewriting. Supported commands include punctuation (`comma`, `full stop`, `question mark`, `colon`), symbols (`percent`, `at sign`, `hash`, currency signs, operators, slashes, `underscore`), paired brackets or quotes, and `new line` or `new paragraph`.

Select **Rules** to view the read-only built-in commands and add literal custom commands. Select **Help** there for creation, spacing, editing, and deletion guidance. Enter the phrase ClearDictate should recognize, its written replacement, and how the replacement joins surrounding text. **Attach left** is suitable for `%`, **Attach right** for currency or hashtag prefixes, **Attach both** for email or identifier symbols, and **Keep spaces** for operators. Enable automatic-punctuation removal when the recognizer commonly appends punctuation to the command itself. Custom rules take precedence over built-ins and apply to the next PC or phone dictation without restarting.

ClearDictate permits one desktop instance per Windows session. Launching it again restores and focuses the existing main window, then the duplicate process exits before loading models or opening another phone listener.

Select **History** to open the retained-record viewer. It lists newest records first and can filter by the PC's local calendar date. The **Polish result** column identifies
successful Qwen output and names the reason for deterministic fallback; records created before this provenance was stored show **Not recorded**. Hover an ASR,
selected-text, or reviewed-correction cell and click its **Copy** affordance to place exactly that cell's text on the clipboard; click elsewhere on the row to play its
stored WAV through the default Windows output. A reviewed correction can be saved separately from the immutable ASR and selected outputs. The correction and its UTC
review time are retained in the same local SQLite database for regression testing and explicit dataset preparation. **Refresh** reloads records created while the viewer
is open.

## Developer phone endpoint

After both models reach **Ready**, ClearDictate listens on port `8765` for the versioned completed-audio protocol. Open **Phone**, choose the address on the phone's
Wi-Fi network when several are available, and scan its QR code from the Android application. The address and persistent bearer token remain selectable as a manual
fallback. The service accepts authenticated 16 kHz mono PCM16 recordings at
`/v1/dictation`, runs the same serialized Qwen3-ASR and Qwen3.5 pipeline used by desktop capture, and returns only the polished UTF-8 transcript.

The Android Debug recorder and keyboard use this endpoint after QR or manual pairing. The transport is authenticated but not encrypted, so use it only on a trusted
private network. Certificate-based pairing remains required before production use.
