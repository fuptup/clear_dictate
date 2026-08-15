# Third-party notices

ClearDictate source is licensed under the Apache License 2.0. The following third-party components and model files are used or pinned by the project. Their own licences continue to apply.

## llama.cpp

- Source: `ggml-org/llama.cpp`
- Pinned release: `b10189`
- Pinned commit: `b2f221684fcd898e947a121baeda80f345da3e6b`
- Licence: MIT
- Copyright: 2023-2026 The ggml authors

The complete MIT licence text is retained at `third_party/llama.cpp/LICENSE`.

## Qwen 2.5 0.5B Instruct model

- Source: `Qwen/Qwen2.5-0.5B-Instruct-GGUF`
- Pinned revision: `9217f5db79a29953eb74d5343926648285ec7e67`
- Selected file: `qwen2.5-0.5b-instruct-q4_k_m.gguf`
- Licence identifier: Apache-2.0

The model is not committed to this repository or bundled in the current application package.
Its license text and pinned provenance are retained under `third_party/qwen`.

## Qwen3-ASR 1.7B model

- Source: `Qwen/Qwen3-ASR-1.7B`
- Pinned revision: `7278e1e70fe206f11671096ffdd38061171dd6e5`
- Licence identifier: Apache-2.0

The model is downloaded into the isolated WSL ClearDictate directory. Runtime file lengths and digests are pinned in `gpu-worker/qwen3-asr-1.7b-lock.json`.

The development runtime uses `qwen-asr` 0.0.6 and `vllm` 0.14.0 under their published Apache License 2.0 terms. They and their Python/CUDA dependencies are installed into
the local WSL environment and are not bundled in the current Windows application image.

## Qwen3.5 9B Q6_K model

- Derived model source: `unsloth/Qwen3.5-9B-GGUF`
- Pinned revision: `3885219b6810b007914f3a7950a8d1b469d598a5`
- Selected file: `Qwen3.5-9B-Q6_K.gguf`
- Original model: `Qwen/Qwen3.5-9B`
- Licence identifier: Apache-2.0

The GGUF file is not committed or bundled. Its exact provenance, byte count, and digest are pinned in `native-worker/dependencies/qwen-model-lock.cmake`.

## Moonshine Voice and Tiny Streaming English model

- Source: `moonshine-ai/moonshine`
- Pinned release commit: `cc1695646a560f2eec7f7c058f3c4d580f039e4b`
- Licence for Moonshine code outside `core/third-party`: MIT
- Licence for the English-language model: MIT
- Copyright: 2025 Useful Sensors, Inc. (doing business as Moonshine AI)

ClearDictate links Moonshine only into Android development artifacts. The published Android archive is not shipped unchanged: ClearDictate builds the exact pinned source with the documented memory-safety/status patch in `third_party/moonshine`.

The linked native dependency tree includes:

- ONNX Runtime — MIT;
- Eigen — Mozilla Public License 2.0;
- kaldi-native-fbank — Apache License 2.0;
- kissfft — revised BSD licence;
- nlohmann JSON and doctest — MIT;
- utf-8 — Boost Software License 1.0;
- utf8proc — its bundled permissive licence;
- cpp-annotate — MIT.

The authoritative licence files used by this native build are retained under
`third_party/moonshine/LICENSE` and `third_party/moonshine/licenses`. A future public binary still
needs an in-application notice surface and a generated dependency inventory. The present Android
output is a Debug development artifact, not a distributable release.

## Java Native Access

- Component: `net.java.dev.jna:jna`
- Version: `5.18.1`
- Licence used by ClearDictate: Apache-2.0

Java Native Access is dual-licensed under the GNU Lesser General Public License 2.1-or-later or Apache License 2.0. ClearDictate elects the Apache License 2.0 option.

## SQLite JDBC

- Component: `org.xerial:sqlite-jdbc`
- Version: `3.53.2.1`
- Licences: Apache-2.0 and BSD-2-Clause

SQLite JDBC provides the local Windows SQLite driver and bundles platform-native SQLite libraries in its runtime JAR.

## Kotlin, Jetpack Compose, and AndroidX

The project uses Kotlin, Jetpack Compose, and AndroidX libraries under their published Apache License 2.0 terms. Exact resolved versions are declared in `gradle/libs.versions.toml` and the Android Compose Bill of Materials.

## Distribution status

The current Windows and Android outputs are unsigned Debug development artifacts. No redistributable installer or store package is produced. Before distributing an
application binary, generate a complete machine-readable dependency inventory and bundle every required upstream licence and notice.
