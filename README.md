# ClearDictate

ClearDictate is an offline dictation project targeting Android and Windows. It is being built as a native Android application and Android Input Method Editor, with a Windows development application for exercising the same deterministic transcript-cleaning and local text-polishing behavior.

Current verified capabilities:

- deterministic Raw and Clean transcript processing;
- protected-value validation and fail-closed Polished fallback;
- Android Input Method Editor domain policies and a buildable Android application scaffold;
- a bounded, versioned Kotlin/C++ worker protocol;
- a persistent Windows worker using the pinned Qwen2.5 0.5B model through pinned llama.cpp;
- cryptographic model verification before same-handle loading;
- cancellation that is complete before the public cancellation call returns;
- a real Kotlin-to-worker integration test through the semantic-safety pipeline;
- a Windows text-pipeline developer preview with editable input/output, all three transcript modes, cancellation, fallback visibility, clipboard copying, and explicit worker restart.

Important current limitations:

- Windows microphone capture and Moonshine speech recognition are not connected yet;
- the Android application remains incomplete relative to the full product specification;
- the Windows executable is a Debug development artifact and depends on Microsoft Debug runtimes; it is not a distributable installer;
- measured tests on the target Motorola Edge+ phones have not yet been performed.

See [Windows development instructions](docs/WINDOWS_DEVELOPMENT.md) for the exact local build and test workflow.

On the configured development computer, double-click `Run-ClearDictate-Developer-Preview.cmd` to open the text-only Windows harness. This launcher is a convenience for the existing Debug toolchain, not a redistributable installer.

ClearDictate source is licensed under the [Apache License 2.0](LICENSE). See [third-party notices](THIRD_PARTY_NOTICES.md) for pinned dependencies and models.
