# Windows development

## Scope

The current Windows slice is a developer integration target for transcript cleaning, local Qwen polishing, cancellation, semantic validation, and the pinned Moonshine streaming engine. It is not yet a complete Windows dictation product because the native microphone worker and user interface are not connected.

The Moonshine milestone is deliberately a deterministic engine test rather than a microphone demo. It verifies and leases all seven model components, streams a real spoken WAV fixture in irregular chunks, copies partial-line state before Moonshine invalidates it, forces the final transcript, and exercises cancellation cleanup. Audio capture will live in a separate native worker using event-driven shared-mode Windows Audio Session API capture and a bounded reusable buffer queue; raw microphone audio will not cross the Java process pipe.

This remains Debug-only developer infrastructure. Upstream Moonshine currently merges unrelated text-to-speech code and VCTK-derived voice assets into its Windows static library. ClearDictate will not produce a redistributable Windows package until that code is excluded cleanly and the resulting native license set is complete.

All commands below build and test the Debug configuration. The Debug worker depends on Microsoft Debug runtimes and is suitable only for a computer with the Visual Studio C++ development tools installed.

The verified development computer currently uses:

- Windows 10.0.19045;
- Visual Studio 2022 Developer Command Prompt 17.14.34 with the x64 C++ workload;
- Windows Software Development Kit 10.0.26100.0;
- CMake 3.31.6;
- Git 2.50.1 for Windows;
- Git Large File Storage 3.7.0;
- Java 17 from the Unity 6000.4.8f1 Android toolchain;
- Android platform and build tools 36.0.0.

## Locked native dependencies

- llama.cpp release: `b10189`
- llama.cpp commit: `b2f221684fcd898e947a121baeda80f345da3e6b`
- Qwen repository revision: `9217f5db79a29953eb74d5343926648285ec7e67`
- Qwen file: `qwen2.5-0.5b-instruct-q4_k_m.gguf`
- Qwen byte count: `491400032`
- Qwen Secure Hash Algorithm 256-bit digest: `74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db`
- Moonshine release: `v0.1.0`
- Moonshine commit: `cc1695646a560f2eec7f7c058f3c4d580f039e4b`
- spoken fixture: upstream `test-assets/two_cities_16k.wav`, `1420382` bytes, digest `14a5a09479cab470db7de4fd16c0e18a8a88c03cc6d92a09a658e857ee1f73a5`

The build checks both exact source commits and rejects changes, including untracked files in the Windows Moonshine checkout. Moonshine is clean-built from that checkout as part of the ClearDictate build graph. The worker checks the Qwen byte count and digest before it passes the same open file handle to llama.cpp. The speech engine verifies and holds deny-write leases on every Moonshine model component for the complete transcriber lifetime.

The pinned Moonshine Windows loader does not correctly interpret arbitrary non-ASCII narrow-character model paths. Until that upstream boundary is patched and tested, keep the repository, build tree, and speech-model directory on an ASCII-only path.

## Local directory layout

The development commands expect:

```text
E:\VoiceToText\.tooling\upstream\llama.cpp-2026-07-30
E:\VoiceToText\.tooling\upstream\moonshine-windows-clean
E:\VoiceToText\.tooling\models\qwen2.5-0.5b-instruct\qwen2.5-0.5b-instruct-q4_k_m.gguf
E:\VoiceToText\.tooling\models\moonshine-tiny-streaming-en
```

The `.tooling` directory and model files are intentionally excluded from Git.

## Recreate the pinned Windows inputs

From a clean checkout, create the ignored tooling directories:

```powershell
New-Item -ItemType Directory -Force -Path .tooling\upstream
New-Item -ItemType Directory -Force -Path .tooling\models\qwen2.5-0.5b-instruct
New-Item -ItemType Directory -Force -Path .tooling\models\moonshine-tiny-streaming-en
```

Clone llama.cpp and detach the checkout at the exact reviewed commit:

```powershell
git clone https://github.com/ggml-org/llama.cpp.git .tooling\upstream\llama.cpp-2026-07-30
git -C .tooling\upstream\llama.cpp-2026-07-30 checkout --detach b2f221684fcd898e947a121baeda80f345da3e6b
```

Clone Moonshine without hydrating its very large unrelated assets, then hydrate only the Windows runtime and spoken fixture used by this Debug milestone:

```powershell
$env:GIT_LFS_SKIP_SMUDGE='1'
git lfs install
git clone https://github.com/moonshine-ai/moonshine.git .tooling\upstream\moonshine-windows-clean
git -C .tooling\upstream\moonshine-windows-clean checkout --detach cc1695646a560f2eec7f7c058f3c4d580f039e4b
git -C .tooling\upstream\moonshine-windows-clean lfs pull --include="core/cpp-annote/src/community1_cpp_annote_embedded.cpp,core/cpp-annote/src/community1_ort_embedded.cpp,core/moonshine-tts/src/zipvoice-voices-data.cpp,core/third-party/onnxruntime/lib/windows/x86_64/*,test-assets/two_cities_16k.wav" --exclude=""
Remove-Item Env:GIT_LFS_SKIP_SMUDGE
```

Download the pinned Qwen file directly from its immutable repository revision:

```powershell
curl.exe -L --fail --output .tooling\models\qwen2.5-0.5b-instruct\qwen2.5-0.5b-instruct-q4_k_m.gguf https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/9217f5db79a29953eb74d5343926648285ec7e67/qwen2.5-0.5b-instruct-q4_k_m.gguf
curl.exe -L --fail --output .tooling\models\moonshine-tiny-streaming-en\adapter.ort https://download.moonshine.ai/model/tiny-streaming-en/quantized/adapter.ort
curl.exe -L --fail --output .tooling\models\moonshine-tiny-streaming-en\cross_kv.ort https://download.moonshine.ai/model/tiny-streaming-en/quantized/cross_kv.ort
curl.exe -L --fail --output .tooling\models\moonshine-tiny-streaming-en\decoder_kv.ort https://download.moonshine.ai/model/tiny-streaming-en/quantized/decoder_kv.ort
curl.exe -L --fail --output .tooling\models\moonshine-tiny-streaming-en\encoder.ort https://download.moonshine.ai/model/tiny-streaming-en/quantized/encoder.ort
curl.exe -L --fail --output .tooling\models\moonshine-tiny-streaming-en\frontend.ort https://download.moonshine.ai/model/tiny-streaming-en/quantized/frontend.ort
curl.exe -L --fail --output .tooling\models\moonshine-tiny-streaming-en\streaming_config.json https://download.moonshine.ai/model/tiny-streaming-en/quantized/streaming_config.json
curl.exe -L --fail --output .tooling\models\moonshine-tiny-streaming-en\tokenizer.bin https://download.moonshine.ai/model/tiny-streaming-en/quantized/tokenizer.bin
```

Verify the downloaded file before configuring the native build:

```powershell
Get-Item .tooling\models\qwen2.5-0.5b-instruct\qwen2.5-0.5b-instruct-q4_k_m.gguf | Select-Object Length
Get-FileHash -Algorithm SHA256 .tooling\models\qwen2.5-0.5b-instruct\qwen2.5-0.5b-instruct-q4_k_m.gguf
Get-ChildItem .tooling\models\moonshine-tiny-streaming-en -File | Get-FileHash -Algorithm SHA256
Get-FileHash -Algorithm SHA256 .tooling\upstream\moonshine-windows-clean\test-assets\two_cities_16k.wav
```

The required length is `491400032` bytes and the required digest is `74A4DA8C9FDBCD15BD1F6D01D621410D31C6FC00986F5EB687824E7B93D7A9DB`. The native integration test and runtime worker independently reject a mismatch.

The Moonshine model files are locked as follows:

| File | Bytes | Secure Hash Algorithm 256-bit digest |
| --- | ---: | --- |
| `adapter.ort` | 1319440 | `df13e655b29d279911fcb42d8b91b0e655b8fe32b7ba1f463ece663ce55ae6eb` |
| `cross_kv.ort` | 1264384 | `5acfca68f7bb068c68c1960b54e215995ba07ee46b61645b78bff010a14e5a92` |
| `decoder_kv.ort` | 32403688 | `6e3828f1db4b634bc525cb8ba1f0b628ec56059168f0336ad060891c7c1c9154` |
| `encoder.ort` | 7569200 | `96dde726be90c4429f3bc458d04e3ea5bd1818a5fdcd0152edf4c07b8e405c07` |
| `frontend.ort` | 8324600 | `bbdf5edb120cb3df1adf9ebc07c35136539b007a7047fd148c6f2960fc56fcf1` |
| `streaming_config.json` | 509 | `74fe5ddebd63b17caf59e8a3b18c17547ff7bce1642050edbb1c3962674f8950` |
| `tokenizer.bin` | 249974 | `6884b35fd6377d4c4d32336a0bc152f36b64d1e45b6503683cdc238250a8472d` |

## Configure the native build

Open PowerShell in the repository root. The following command starts a Visual Studio x64 developer environment and asks CMake to generate the native build tree. The options select the Debug-tested central processing unit backend, enable the project adapter, and identify the pinned local dependency and model:

```powershell
cmd.exe /d /v:on /c 'set "SAVED_BUILD_PATH=!PATH!" && set "Path=" && set "PATH=!SAVED_BUILD_PATH!" && call "C:\Program Files\Microsoft Visual Studio\2022\Community\Common7\Tools\VsDevCmd.bat" -arch=x64 -host_arch=x64 && cmake -S native-worker -B native-worker/build-llama -DCLEAR_DICTATE_ENABLE_LLAMA=ON -DCLEAR_DICTATE_ENABLE_MODEL_INTEGRATION_TESTS=ON -DLLAMA_CPP_SOURCE_DIR=E:/VoiceToText/.tooling/upstream/llama.cpp-2026-07-30 -DCLEAR_DICTATE_TEST_TEXT_MODEL=E:/VoiceToText/.tooling/models/qwen2.5-0.5b-instruct/qwen2.5-0.5b-instruct-q4_k_m.gguf -DCLEAR_DICTATE_ENABLE_MOONSHINE=ON -DMOONSHINE_SOURCE_DIR=E:/VoiceToText/.tooling/upstream/moonshine-windows-clean -DMOONSHINE_BUILD_DIR=E:/VoiceToText/.tooling/upstream/moonshine-windows-clean/build-cleardictate-debug -DCLEAR_DICTATE_ENABLE_SPEECH_MODEL_INTEGRATION_TESTS=ON -DCLEAR_DICTATE_TEST_SPEECH_MODEL_DIRECTORY=E:/VoiceToText/.tooling/models/moonshine-tiny-streaming-en -DCLEAR_DICTATE_TEST_SPEECH_WAVE=E:/VoiceToText/.tooling/upstream/moonshine-windows-clean/test-assets/two_cities_16k.wav'
```

## Build and test native code

This command compiles every native Debug target and runs all CTest tests, including real locked-model generation and cancellation:

```powershell
cmd.exe /d /v:on /c 'set "SAVED_BUILD_PATH=!PATH!" && set "Path=" && set "PATH=!SAVED_BUILD_PATH!" && call "C:\Program Files\Microsoft Visual Studio\2022\Community\Common7\Tools\VsDevCmd.bat" -arch=x64 -host_arch=x64 && cmake --build native-worker/build-llama --config Debug && ctest --test-dir native-worker/build-llama -C Debug --output-on-failure'
```

The resulting development worker is:

```text
E:\VoiceToText\native-worker\build-llama\Debug\clear_dictate_worker.exe
```

The adjacent `clear_dictate_worker_launcher.exe` validates the Java host's process identifier and exact Windows creation timestamp, creates the worker suspended and already bound to a kill-on-close Windows Job Object, rechecks the host, and only then resumes the worker. The Kotlin host launches this lifetime wrapper automatically. This prevents a recycled process identifier from keeping an orphaned model worker alive.

## Build and test Kotlin code

`JAVA_HOME` tells Gradle where Java 17 is installed. `ANDROID_HOME` tells the Android build where the local Android Software Development Kit is installed.

```powershell
$env:JAVA_HOME='C:\Program Files\Unity\Hub\Editor\6000.4.8f1\Editor\Data\PlaybackEngines\AndroidPlayer\OpenJDK'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
$env:ANDROID_HOME='E:\VoiceToText\.tooling\android-sdk'
.\gradlew.bat :core-domain:test :core-input-connection:test :core-models:test :inference-contract:test :desktop-inference:test :desktop-app:test :inference-service:testDebugUnitTest :android-app:testDebugUnitTest :android-app:assembleDebug :android-app:lintDebug
```

## Run the real Kotlin-to-worker integration test

`GRADLE_OPTS` supplies the two required integration-test paths to Gradle without placing transcript text on a command line. The test is explicitly skipped during ordinary test runs when these paths are absent.

```powershell
$env:GRADLE_OPTS='-DclearDictate.workerExecutable=E:/VoiceToText/native-worker/build-llama/Debug/clear_dictate_worker.exe -DclearDictate.textModel=E:/VoiceToText/.tooling/models/qwen2.5-0.5b-instruct/qwen2.5-0.5b-instruct-q4_k_m.gguf'
.\gradlew.bat :desktop-inference:realWorkerIntegrationTest --rerun-tasks
```

The dedicated integration task fails when either path is absent and keeps its HyperText Markup Language and Extensible Markup Language reports separate from the ordinary model-free test task. It verifies empty-input fallback without model startup, real in-flight cancellation, post-cancellation worker reuse, deterministic cleanup, local polishing, and preservation of an identifier, two times, and an explicit negation.

## Run the desktop developer preview

Double-click `Run-ClearDictate-Developer-Preview.cmd` from File Explorer, or run the following command from the repository root:

```powershell
.\gradlew.bat :desktop-app:run
```

The screen is intentionally a text-only development harness. Raw and Clean processing do not start the model worker. The first Polished request starts one persistent serialized worker; later requests reuse it until the application closes or **Restart local worker** is selected. The screen does not capture microphone audio, save transcript history, or claim production latency.

## Privacy boundary

The Windows host launches the worker with anonymous standard input and output pipes. The process boundary provides address-space and crash isolation; it is not a hostile-code sandbox. Transcript and model paths are absent from the worker command line. Worker and upstream inference diagnostics are reduced to fixed transcript-free categories.
