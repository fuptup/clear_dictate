# ClearDictate Moonshine Android patch

ClearDictate pins Moonshine Voice source revision `cc1695646a560f2eec7f7c058f3c4d580f039e4b`
(upstream release `v0.1.0`) and builds its Android library locally.

The published `ai.moonshine:moonshine-voice:0.1.0` Android archive is not used for
shipping because its Java Native Interface obtains Java float-array elements for
every audio submission without releasing them. Depending on the Android Runtime,
that can pin the Java array or leak a native copy for every streaming chunk.

`moonshine-android-memory-safety.patch` adds exception-safe releases with
`JNI_ABORT`, rejects failed acquisitions, and deletes high-frequency local Java
references created while converting transcripts.

The patch does not change the recognition algorithm or model.

The checked-in Android archive is stored through Git Large File Storage:

- file: `artifacts/moonshine-voice-0.1.0-cleardictate-debug.aar`
- Secure Hash Algorithm 256-bit:
  `47503347e1c5e6ebc211d65afbbc982f9d8ea795f87ff9a8544a86003a71303d`

ClearDictate resolves that local archive directly. It does not resolve the unpatched Maven artifact.

The pinned Moonshine license is retained in `LICENSE`; licenses for the linked native dependency
tree are retained in `licenses`.
