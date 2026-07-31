# Windows development

## Scope

The current Windows slice is a developer integration target for live local microphone recognition, transcript cleaning, local Qwen polishing, cancellation, semantic validation, and the pinned Moonshine streaming engine. The microphone path now runs through a dedicated isolated speech worker and is connected to the desktop developer interface. It is not yet a complete Windows dictation product because the system-wide overlay, global shortcut, target-application insertion, history, settings, and device-compatibility work remain incomplete.

The shared Kotlin/C++ worker framing is protocol version 4. It defines an operation-scoped `RecordingStarted` acknowledgement, a separately versioned Moonshine model-directory payload, and a separately versioned recording-start payload whose empty endpoint identifier selects the default console microphone. The host and native state machines distinguish start, active recording, stop, cancellation request, and cancellation acknowledgement. The isolated speech worker owns model loading, a fresh single-use capture producer, the bounded audio queue consumer, partial recognition, finalization, and model teardown on one recognition thread.

Protocol version 4 has no negotiation with the earlier developer protocol. The desktop application, launcher, text worker, and speech worker must be built and deployed atomically from the same source revision. A mixed-version startup fails closed and the launcher process tree is terminated; release packaging remains blocked and therefore does not yet claim an installer-level deployment guarantee.

The deterministic Moonshine engine test verifies and leases all seven model components, streams a real spoken WAV fixture in irregular chunks, copies partial-line state before Moonshine invalidates it, forces the final transcript, and exercises cancellation cleanup. A separate Kotlin-to-speech-worker integration test verifies model loading, live default-microphone capture, finalization, host transcript state, and process shutdown.

The native capture transport opens either one exact selected endpoint identifier or the default console capture endpoint resolved once at recording start. It requests event-driven shared-mode conversion to 16-kilohertz mono 32-bit floating point, copies each borrowed endpoint packet into preallocated scratch, releases the endpoint packet, and only then publishes sanitized samples into a bounded preallocated queue. It preserves explicit endpoint silence as zero-valued frames and fails closed on timing damage, device loss, stalls, or overflow. Normal Stop freezes and drains already-captured endpoint packets; Cancel does not. A wakeable activity channel tells the recognition consumer when audio is available or capture is terminal, so the consumer does not poll an empty queue indefinitely. Raw microphone audio never crosses the Java process pipe.

This capture milestone has been exercised only against the development computer's current default microphone. It does not establish compatibility with other internal, wired, Universal Serial Bus, or Bluetooth devices; Windows privacy-denial behavior; device changes; sleep/resume; sustained load; long recordings; input-level metering; or recognition accuracy for live microphone speech. The three-second capture-stall threshold is provisional and requires device testing before release.

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
E:\VoiceToText\.tooling\models\moonshine-medium-streaming-en
```

The `.tooling` directory and model files are intentionally excluded from Git. The Windows preview uses Medium Streaming because its larger recognition model materially improves accuracy while the isolated, bounded worker architecture keeps the extra memory and computation outside the user-interface process. Android remains on Tiny Streaming until Medium has been measured on representative phones.

## Recreate the pinned Windows inputs

From a clean checkout, create the ignored tooling directories:

```powershell
New-Item -ItemType Directory -Force -Path .tooling\upstream
New-Item -ItemType Directory -Force -Path .tooling\models\qwen2.5-0.5b-instruct
New-Item -ItemType Directory -Force -Path .tooling\models\moonshine-medium-streaming-en
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
curl.exe -L --fail --output .tooling\models\moonshine-medium-streaming-en\adapter.ort https://download.moonshine.ai/model/medium-streaming-en/quantized/adapter.ort
curl.exe -L --fail --output .tooling\models\moonshine-medium-streaming-en\cross_kv.ort https://download.moonshine.ai/model/medium-streaming-en/quantized/cross_kv.ort
curl.exe -L --fail --output .tooling\models\moonshine-medium-streaming-en\decoder_kv.ort https://download.moonshine.ai/model/medium-streaming-en/quantized/decoder_kv.ort
curl.exe -L --fail --output .tooling\models\moonshine-medium-streaming-en\decoder_kv_with_attention.ort https://download.moonshine.ai/model/medium-streaming-en/quantized/decoder_kv_with_attention.ort
curl.exe -L --fail --output .tooling\models\moonshine-medium-streaming-en\encoder.ort https://download.moonshine.ai/model/medium-streaming-en/quantized/encoder.ort
curl.exe -L --fail --output .tooling\models\moonshine-medium-streaming-en\frontend.ort https://download.moonshine.ai/model/medium-streaming-en/quantized/frontend.ort
curl.exe -L --fail --output .tooling\models\moonshine-medium-streaming-en\streaming_config.json https://download.moonshine.ai/model/medium-streaming-en/quantized/streaming_config.json
curl.exe -L --fail --output .tooling\models\moonshine-medium-streaming-en\tokenizer.bin https://download.moonshine.ai/model/medium-streaming-en/quantized/tokenizer.bin
```

Verify the downloaded file before configuring the native build:

```powershell
Get-Item .tooling\models\qwen2.5-0.5b-instruct\qwen2.5-0.5b-instruct-q4_k_m.gguf | Select-Object Length
Get-FileHash -Algorithm SHA256 .tooling\models\qwen2.5-0.5b-instruct\qwen2.5-0.5b-instruct-q4_k_m.gguf
Get-ChildItem .tooling\models\moonshine-medium-streaming-en -File | Get-FileHash -Algorithm SHA256
Get-FileHash -Algorithm SHA256 .tooling\upstream\moonshine-windows-clean\test-assets\two_cities_16k.wav
```

The required length is `491400032` bytes and the required digest is `74A4DA8C9FDBCD15BD1F6D01D621410D31C6FC00986F5EB687824E7B93D7A9DB`. The native integration test and runtime worker independently reject a mismatch.

The Moonshine model files are locked as follows:

| File | Bytes | Secure Hash Algorithm 256-bit digest |
| --- | ---: | --- |
| `adapter.ort` | 3647712 | `16307442b7f4229f2f1511fc51b545cec9616e55872c588f3a297bbc6f4762ea` |
| `cross_kv.ort` | 11544952 | `354b9a955caeb768b528f447f0a36ce4b850ca7b4531900165df304d97904fba` |
| `decoder_kv.ort` | 146216448 | `fa67aa87521247f5bf44d3e44d4e4978e58c1f114249c3c6909c882624056715` |
| `decoder_kv_with_attention.ort` | 146138304 | `40919de95d08690da3a8ff6df14cf55b3220046f3b767b4a4b769e7b32aaf2d2` |
| `encoder.ort` | 94202872 | `a5f11167a62eef61787fe8410453257d6ddb8eba90af461a9604e5f2e93d5322` |
| `frontend.ort` | 47467256 | `378fe8a5d7090a1b9ab88bbb1fc95bde010cdd64ec23419350d2d23c675636e9` |
| `streaming_config.json` | 513 | `28e83b7a28e91472692a035e0dae3116422ae43aeb2bef5ed822c44ce89b88af` |
| `tokenizer.bin` | 249974 | `6884b35fd6377d4c4d32336a0bc152f36b64d1e45b6503683cdc238250a8472d` |

## Configure the native build

Open PowerShell in the repository root. The following command starts a Visual Studio x64 developer environment and asks CMake to generate the native build tree. The options select the Debug-tested central processing unit backend, enable the project adapter, and identify the pinned local dependency and model:

```powershell
cmd.exe /d /v:on /c 'set "SAVED_BUILD_PATH=!PATH!" && set "Path=" && set "PATH=!SAVED_BUILD_PATH!" && call "C:\Program Files\Microsoft Visual Studio\2022\Community\Common7\Tools\VsDevCmd.bat" -arch=x64 -host_arch=x64 && cmake -S native-worker -B native-worker/build-llama -DCLEAR_DICTATE_ENABLE_LLAMA=ON -DCLEAR_DICTATE_ENABLE_MODEL_INTEGRATION_TESTS=ON -DLLAMA_CPP_SOURCE_DIR=E:/VoiceToText/.tooling/upstream/llama.cpp-2026-07-30 -DCLEAR_DICTATE_TEST_TEXT_MODEL=E:/VoiceToText/.tooling/models/qwen2.5-0.5b-instruct/qwen2.5-0.5b-instruct-q4_k_m.gguf -DCLEAR_DICTATE_ENABLE_MOONSHINE=ON -DMOONSHINE_SOURCE_DIR=E:/VoiceToText/.tooling/upstream/moonshine-windows-clean -DMOONSHINE_BUILD_DIR=E:/VoiceToText/.tooling/upstream/moonshine-windows-clean/build-cleardictate-debug -DCLEAR_DICTATE_ENABLE_SPEECH_MODEL_INTEGRATION_TESTS=ON -DCLEAR_DICTATE_TEST_SPEECH_MODEL_DIRECTORY=E:/VoiceToText/.tooling/models/moonshine-medium-streaming-en -DCLEAR_DICTATE_TEST_SPEECH_WAVE=E:/VoiceToText/.tooling/upstream/moonshine-windows-clean/test-assets/two_cities_16k.wav'
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

The screen is intentionally a developer harness rather than the final dictation interaction. Its microphone selector lists active Windows capture endpoints without opening them. **System default** resolves the current Windows default once when recording starts; selecting a named input passes that exact endpoint identifier to the isolated speech worker. **Record** lazily starts that worker, verifies the Moonshine model, opens the selected microphone, and streams the latest transcript into the source editor. **Stop and transcribe** drains accepted audio and publishes the final transcript. **Cancel recording** waits for native cancellation completion and clears the live transcript. Raw and Clean processing do not start the text worker. The first Polished request starts one persistent serialized text worker; later requests reuse it until the application closes or **Restart local worker** is selected. The screen does not save audio or transcript history and does not claim production latency.

## Run the real Kotlin-to-speech-worker integration test

The live integration test is skipped unless all three explicit properties are supplied. It opens the default microphone for three seconds, keeps audio in bounded memory, finalizes the transcript locally, verifies the host state, and shuts down the worker:

```powershell
.\gradlew.bat :desktop-inference:test --tests com.cleardictate.desktop.inference.WindowsSpeechWorkerClientIntegrationTest '-DclearDictate.speechWorkerExecutable=E:\VoiceToText\native-worker\build-llama\Debug\clear_dictate_speech_worker.exe' '-DclearDictate.speechModelDirectory=E:\VoiceToText\.tooling\models\moonshine-medium-streaming-en' '-DclearDictate.allowLiveMicrophone=true'
```

## Run the opt-in microphone transport smoke test

The following Debug-only diagnostic opens the default console microphone for two seconds, keeps samples only in bounded memory, drains and overwrites the consumer buffer, scrubs the queue, and prints only frame counts and fixed error categories:

```powershell
.\native-worker\build-llama\Debug\clear_dictate_windows_audio_session_capture_smoke.exe --allow-live-microphone-capture
```

The explicit argument prevents automated test discovery or an accidental launch from activating the microphone. This is a transport smoke test only: it does not invoke Moonshine, create a transcript, save audio, prove device compatibility, or test the desktop user interface.

The following separate Debug-only concurrency diagnostic starts producer joining while capture is still active, then proves the nonblocking cancellation signal returns without waiting behind that join. The 250-millisecond signal threshold is a regression-test allowance, not a product latency claim:

```powershell
.\native-worker\build-llama\Debug\clear_dictate_windows_audio_session_capture_cancellation_smoke.exe --allow-live-microphone-cancel-overlap
```

The following separate Debug-only diagnostic connects the same capture transport to the verified Moonshine model for three seconds. It consumes audio concurrently, scrubs reusable audio and partial-transcript buffers, and prints only frame, delta, and final-text byte counts by default:

```powershell
.\native-worker\build-llama\Debug\clear_dictate_live_microphone_moonshine_pipeline_smoke.exe --allow-live-microphone-moonshine-pipeline E:\VoiceToText\.tooling\models\moonshine-medium-streaming-en
```

Add `--print-transcript` only when you deliberately want recognized ambient speech written to persistent console scrollback. Silence and an empty transcript are valid for this diagnostic: success requires a nonzero frame count and proves every accepted microphone frame reached the live Moonshine pipeline on the development computer, while the separate pinned spoken-fixture test proves recognition output. This diagnostic remains useful below the isolated-worker boundary; it does not prove the device-compatibility matrix or real-time headroom.

Windows endpoint activation and stream initialization are operating-system calls that cannot be interrupted safely from inside the capture thread. The desktop host therefore applies bounded startup and cancellation deadlines; when a deadline is missed, it terminates the isolated speech-worker process tree through the existing Windows Job Object lifetime boundary. The in-process capture primitive alone does not claim bounded cancellation while Windows is inside those initialization calls.

## Privacy boundary

The Windows host launches the worker with anonymous standard input and output pipes. The process boundary provides address-space and crash isolation; it is not a hostile-code sandbox. Transcript and model paths are absent from the worker command line. Worker and upstream inference diagnostics are reduced to fixed transcript-free categories.
