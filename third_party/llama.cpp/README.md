# ClearDictate llama.cpp Android patch

ClearDictate pins llama.cpp source revision `b2f221684fcd898e947a121baeda80f345da3e6b`
(upstream build `b10189`) and builds its Android library locally.

`llama-android-cleardictate.patch` adapts the upstream Android example library for
deterministic, cancellable transcript polishing:

- accepts explicit context, thread, temperature, top-p, and seed settings;
- resets the sampler and system prompt for every independent transcript;
- rejects prompts that exceed the context instead of truncating user text;
- stops generation through a native cancellation flag checked at token boundaries;
- removes prompt and generated-text logging;
- clears native handles after unload; and
- supports ClearDictate's Android 9 minimum without calling Android 11-only logging APIs.

The patch also removes the upstream example's forced Release native build. The
checked-in artifact is intentionally Debug-only until a separately reviewed Release
artifact is built; ClearDictate's Release dependency does not silently consume it.

The checked-in Android archive is stored through Git Large File Storage:

- file: `artifacts/llama-android-b10189-cleardictate-debug.aar`
- Secure Hash Algorithm 256-bit:
  `a45a73e2f452908b79f488c7de9c3df199ffdd768b5fbad8be934fe9fdd27221`

The archive contains `arm64-v8a` and `x86_64` native libraries and was built with:

- Android Native Development Kit `28.2.13676358`;
- CMake `3.31.6`;
- Android minimum application programming interface level `28`; and
- Debug build type.

The pinned upstream license is preserved in `LICENSE`.
