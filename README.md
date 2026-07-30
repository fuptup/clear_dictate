# ClearDictate

ClearDictate is an offline dictation project targeting Android and Windows. It is being built as a native Android application and Android Input Method Editor, with a Windows development application for exercising the same deterministic transcript-cleaning and local text-polishing behavior.

Current implemented capabilities, with verification boundaries listed below:

- deterministic Raw and Clean transcript processing;
- protected-value validation and fail-closed Polished fallback;
- an Android standalone recording screen and system Input Method Editor with private-process speech and text inference ownership, microphone capture, resumable model download, review/insert, and sensitive-field blocking;
- a bounded, versioned Kotlin/C++ worker protocol;
- a persistent Windows worker using the pinned Qwen2.5 0.5B model through pinned llama.cpp;
- cryptographic model verification before same-handle loading;
- a synchronous, drained cancellation contract in the Windows worker and asynchronous cancellation fencing, native drain, and process watchdogs on Android;
- a real Kotlin-to-worker integration test through the semantic-safety pipeline;
- a Windows text-pipeline developer preview with editable input/output, all three transcript modes, cancellation, fallback visibility, clipboard copying, and explicit worker restart.

Important current limitations:

- the pinned Windows Moonshine streaming engine now passes a real spoken-fixture test, but the isolated microphone worker and desktop user interface are not connected yet;
- Android history, full settings/profile screens, a hybrid typing keyboard, and measured phone performance remain incomplete;
- keyboard microphone eligibility still needs physical validation on Android 14, 15, and 16 while the host application is foregrounded;
- the locally patched Moonshine Android library needs a long-duration device soak before the Android slice can be called production-ready;
- the locally patched llama.cpp Android library and Qwen polishing path have build and host-side test coverage but have not yet run on a physical Android device;
- the Windows executable is a Debug development artifact and depends on Microsoft Debug runtimes; it is not a distributable installer;
- measured tests on the target Motorola Edge+ phones have not yet been performed.

See [Windows development instructions](docs/WINDOWS_DEVELOPMENT.md) and [Android development instructions](docs/ANDROID_DEVELOPMENT.md) for the exact local workflows.

On the configured development computer, double-click `Run-ClearDictate-Developer-Preview.cmd` to open the text-only Windows harness. This launcher is a convenience for the existing Debug toolchain, not a redistributable installer.

ClearDictate source is licensed under the [Apache License 2.0](LICENSE). See [third-party notices](THIRD_PARTY_NOTICES.md) for pinned dependencies and models.
