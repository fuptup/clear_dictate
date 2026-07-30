# Windows development

## Scope

The current Windows slice is a developer integration target for transcript cleaning, local Qwen polishing, cancellation, and semantic validation. It is not yet a complete Windows dictation product because microphone capture and Moonshine speech recognition are not connected.

All commands below build and test the Debug configuration. The Debug worker depends on Microsoft Debug runtimes and is suitable only for a computer with the Visual Studio C++ development tools installed.

The verified development computer currently uses:

- Windows 10.0.19045;
- Visual Studio 2022 Developer Command Prompt 17.14.34 with the x64 C++ workload;
- Windows Software Development Kit 10.0.26100.0;
- CMake 3.31.6;
- Git 2.50.1 for Windows;
- Java 17 from the Unity 6000.2.7f2 Android toolchain;
- Android platform and build tools 36.0.0.

## Locked native dependencies

- llama.cpp release: `b10189`
- llama.cpp commit: `b2f221684fcd898e947a121baeda80f345da3e6b`
- Qwen repository revision: `9217f5db79a29953eb74d5343926648285ec7e67`
- Qwen file: `qwen2.5-0.5b-instruct-q4_k_m.gguf`
- Qwen byte count: `491400032`
- Qwen Secure Hash Algorithm 256-bit digest: `74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db`

The build checks the exact llama.cpp commit and rejects tracked changes in that checkout. The worker checks the Qwen byte count and digest before it passes the same open file handle to llama.cpp.

## Local directory layout

The development commands expect:

```text
E:\VoiceToText\.tooling\upstream\llama.cpp-2026-07-30
E:\VoiceToText\.tooling\models\qwen2.5-0.5b-instruct\qwen2.5-0.5b-instruct-q4_k_m.gguf
```

The `.tooling` directory and model files are intentionally excluded from Git.

## Recreate the pinned Windows inputs

From a clean checkout, create the ignored tooling directories:

```powershell
New-Item -ItemType Directory -Force -Path .tooling\upstream
New-Item -ItemType Directory -Force -Path .tooling\models\qwen2.5-0.5b-instruct
```

Clone llama.cpp and detach the checkout at the exact reviewed commit:

```powershell
git clone https://github.com/ggml-org/llama.cpp.git .tooling\upstream\llama.cpp-2026-07-30
git -C .tooling\upstream\llama.cpp-2026-07-30 checkout --detach b2f221684fcd898e947a121baeda80f345da3e6b
```

Download the pinned Qwen file directly from its immutable repository revision:

```powershell
curl.exe -L --fail --output .tooling\models\qwen2.5-0.5b-instruct\qwen2.5-0.5b-instruct-q4_k_m.gguf https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/9217f5db79a29953eb74d5343926648285ec7e67/qwen2.5-0.5b-instruct-q4_k_m.gguf
```

Verify the downloaded file before configuring the native build:

```powershell
Get-Item .tooling\models\qwen2.5-0.5b-instruct\qwen2.5-0.5b-instruct-q4_k_m.gguf | Select-Object Length
Get-FileHash -Algorithm SHA256 .tooling\models\qwen2.5-0.5b-instruct\qwen2.5-0.5b-instruct-q4_k_m.gguf
```

The required length is `491400032` bytes and the required digest is `74A4DA8C9FDBCD15BD1F6D01D621410D31C6FC00986F5EB687824E7B93D7A9DB`. The native integration test and runtime worker independently reject a mismatch.

## Configure the native build

Open PowerShell in the repository root. The following command starts a Visual Studio x64 developer environment and asks CMake to generate the native build tree. The options select the Debug-tested central processing unit backend, enable the project adapter, and identify the pinned local dependency and model:

```powershell
cmd.exe /d /v:on /c 'set "SAVED_BUILD_PATH=!PATH!" && set "Path=" && set "PATH=!SAVED_BUILD_PATH!" && call "C:\Program Files\Microsoft Visual Studio\2022\Community\Common7\Tools\VsDevCmd.bat" -arch=x64 -host_arch=x64 && cmake -S native-worker -B native-worker/build-llama -DCLEAR_DICTATE_ENABLE_LLAMA=ON -DCLEAR_DICTATE_ENABLE_MODEL_INTEGRATION_TESTS=ON -DLLAMA_CPP_SOURCE_DIR=E:/VoiceToText/.tooling/upstream/llama.cpp-2026-07-30 -DCLEAR_DICTATE_TEST_TEXT_MODEL=E:/VoiceToText/.tooling/models/qwen2.5-0.5b-instruct/qwen2.5-0.5b-instruct-q4_k_m.gguf'
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
$env:JAVA_HOME='C:\Program Files\Unity\Hub\Editor\6000.2.7f2\Editor\Data\PlaybackEngines\AndroidPlayer\OpenJDK'
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
