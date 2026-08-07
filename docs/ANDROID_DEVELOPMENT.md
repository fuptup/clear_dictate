# Android development

ClearDictate currently builds an Android Debug application containing the standalone recorder,
system input method, private inference service, a locally patched Moonshine speech engine, and a
locally patched llama.cpp text engine for Qwen polishing.

The inference service also contains the first PC transport client. It encodes one completed 16 kHz mono PCM16 recording with the shared bounded protocol, authenticates to the paired Windows endpoint, returns only the polished transcript, and erases its audio buffer after upload. This client is covered by real HTTP request tests but is not yet connected to the standalone recorder or input method; those surfaces continue to use the on-device inference service in this increment.

## Moonshine native dependency

Moonshine Voice is pinned to commit `cc1695646a560f2eec7f7c058f3c4d580f039e4b`.
Its published `0.1.0` Android archive leaks Java float-array acquisitions in the streaming
Java Native Interface, so it must not be used for a production build.

The reproduced local build uses:

- Android Native Development Kit `28.2.13676358`;
- CMake `3.22.1`;
- Java 17 from the Unity `6000.2.7f2` Android toolchain;
- the patch in `third_party/moonshine/moonshine-android-memory-safety.patch`;
- an upstream archive containing `arm64-v8a`, `armeabi-v7a`, and `x86_64`, from which
  ClearDictate packages only `arm64-v8a` and `x86_64`.

Create and pin a clean upstream checkout from the ClearDictate repository root:

```powershell
git clone https://github.com/moonshine-ai/moonshine.git .tooling\upstream\moonshine-v0.1.0
git -C .tooling\upstream\moonshine-v0.1.0 checkout --detach cc1695646a560f2eec7f7c058f3c4d580f039e4b
git -C .tooling\upstream\moonshine-v0.1.0 apply --unidiff-zero E:\VoiceToText\third_party\moonshine\moonshine-android-memory-safety.patch
git -C .tooling\upstream\moonshine-v0.1.0 diff --check
```

Build only Moonshine's Debug archive with its own Gradle wrapper:

```powershell
$env:JAVA_HOME='C:\Program Files\Unity\Hub\Editor\6000.2.7f2\Editor\Data\PlaybackEngines\AndroidPlayer\OpenJDK'
$env:ANDROID_HOME='E:\VoiceToText\.tooling\android-sdk'
Push-Location .tooling\upstream\moonshine-v0.1.0
.\gradlew.bat assembleDebug
Pop-Location
Copy-Item .tooling\upstream\moonshine-v0.1.0\build\outputs\aar\moonshine-debug.aar third_party\moonshine\artifacts\moonshine-voice-0.1.0-cleardictate-debug.aar
Get-FileHash third_party\moonshine\artifacts\moonshine-voice-0.1.0-cleardictate-debug.aar -Algorithm SHA256
```

The patch releases per-chunk Java audio arrays, checks native status codes, clears per-stream
completion tracking, deletes repeated local references, and sends only changed transcript lines
through the Java event bridge. The required resulting digest is
`47503347E1C5E6EBC211D65AFBBC982F9D8EA795F87FF9A8544A86003A71303D`.

## llama.cpp native dependency

llama.cpp is pinned to upstream build `b10189`, commit
`b2f221684fcd898e947a121baeda80f345da3e6b`. ClearDictate applies
`third_party/llama.cpp/llama-android-cleardictate.patch` and resolves the resulting Debug Android
archive from `third_party/llama.cpp/artifacts`.

The fork pins deterministic generation settings, keeps system and user messages separate, resets
the sampler for every transcript, rejects overlong prompts instead of truncating them, removes
prompt/token logging, and exposes token-boundary cancellation. The archive was built for Android 9
and later with Android Native Development Kit `28.2.13676358` and CMake `3.31.6`.

The Qwen model is not bundled. The application installs the pinned
`Qwen2.5-0.5B-Instruct-GGUF` file alongside Moonshine only after exact filename, length, and Secure
Hash Algorithm 256-bit verification. Polished mode falls back to deterministic Clean text if the
model is absent, native inference fails, times out, or violates protected-value checks.

Build only the Debug variant unless the project owner explicitly authorizes switching variants:

```powershell
.\gradlew.bat assembleDebug
```

## Required physical validation

Before calling the keyboard production-ready, install the Debug Android Package Kit on physical
Android 14, 15, and 16 devices and verify microphone start while ClearDictate is the current input
method over another foreground application. Also run a 30–60 minute streaming soak while tracking
native heap growth, audio routing, permission revocation, screen lock, process death, and input-field
changes. The same physical run must measure first-load and warm Qwen latency, cancellation time,
peak memory, and thermal behavior. Current verification is limited to host-side tests, lint,
native compilation, and Debug package inspection.
