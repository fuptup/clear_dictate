# Third-party notices

ClearDictate source is licensed under the Apache License 2.0. The following third-party components and model files are used or pinned by the project. Their own licences continue to apply.

## llama.cpp

- Source: `ggml-org/llama.cpp`
- Pinned release: `b10189`
- Pinned commit: `b2f221684fcd898e947a121baeda80f345da3e6b`
- Licence: MIT
- Copyright: 2023-2026 The ggml authors

The complete MIT licence text is retained in the pinned development checkout and must be included with any future binary distribution.

## Qwen 2.5 0.5B Instruct model

- Source: `Qwen/Qwen2.5-0.5B-Instruct-GGUF`
- Pinned revision: `9217f5db79a29953eb74d5343926648285ec7e67`
- Selected file: `qwen2.5-0.5b-instruct-q4_k_m.gguf`
- Licence identifier: Apache-2.0

The model is not committed to this repository or bundled in the current application package.

## Moonshine Voice and Tiny Streaming English model

- Source: `moonshine-ai/moonshine`
- Pinned release commit: `cc1695646a560f2eec7f7c058f3c4d580f039e4b`
- Licence for Moonshine code outside `core/third-party`: MIT
- Licence for the English-language model: MIT
- Copyright: 2025 Useful Sensors, Inc. (doing business as Moonshine AI)

Moonshine's `core/third-party` directory contains additional notices that must be carried forward when its native binaries are distributed. Moonshine is pinned and locally evaluated but is not yet linked into a ClearDictate application artifact.

## Java Native Access

- Component: `net.java.dev.jna:jna`
- Version: `5.18.1`
- Licence used by ClearDictate: Apache-2.0

Java Native Access is dual-licensed under the GNU Lesser General Public License 2.1-or-later or Apache License 2.0. ClearDictate elects the Apache License 2.0 option.

## Kotlin, Jetpack Compose, and AndroidX

The project uses Kotlin, Jetpack Compose, and AndroidX libraries under their published Apache License 2.0 terms. Exact resolved versions are declared in `gradle/libs.versions.toml` and the Android Compose Bill of Materials.

## Distribution status

This notice records source dependencies and planned model integration. The current Windows native executables are unsigned Debug development artifacts and no redistributable Windows installer is produced. Before distributing an application binary, generate a complete machine-readable dependency inventory and bundle every required upstream licence and notice.
