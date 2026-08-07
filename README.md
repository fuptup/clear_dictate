# ClearDictate

ClearDictate is a private-network dictation project targeting Android and Windows. The Android recorder and system keyboard capture speech, then the Windows
application runs speech recognition and text polishing on the PC's NVIDIA GPU.

Current implemented capabilities, with verification boundaries listed below:

- deterministic Raw and Clean transcript processing;
- protected-value validation and fail-closed Polished fallback;
- an Android standalone recorder and system Input Method Editor with QR or manual PC pairing, explicit PC reconnect, foreground microphone capture, live input level, processing feedback, review/insert, and sensitive-field blocking;
- a bounded, versioned Kotlin/C++ worker protocol;
- a persistent CUDA Windows text worker using Qwen3.5 9B Q6_K through pinned llama.cpp;
- cryptographic model verification before same-handle loading;
- a synchronous, drained cancellation contract in the Windows worker and asynchronous cancellation fencing on Android;
- a real Kotlin-to-worker integration test through the semantic-safety pipeline;
- a Windows push-to-talk preview with compact microphone selection, release-triggered Qwen3-ASR 1.7B recognition, automatic Qwen3.5 polishing, editable polished output, and clipboard copying;
- a bounded PCM16 phone protocol, authenticated Windows developer endpoint, and Android transport client for sending completed recordings to the PC GPU pipeline.

Important current limitations:

- the Windows push-to-talk path is connected through isolated local workers, but the final system-wide overlay, global shortcut, and target-application insertion are not implemented yet;
- Android history, full settings/profile screens, a hybrid typing keyboard, and measured phone performance remain incomplete;
- keyboard microphone eligibility still needs physical validation on Android 14, 15, and 16 while the host application is foregrounded;
- the Windows executable is a Debug development artifact and depends on Microsoft Debug runtimes; it is not a distributable installer;
- measured tests on the target Motorola Edge+ phones have not yet been performed;
- the current Android/PC endpoint uses authenticated cleartext HTTP in Debug builds and must be used only on a trusted private network until certificate pairing is implemented.

See [Windows development instructions](docs/WINDOWS_DEVELOPMENT.md) and [Android development instructions](docs/ANDROID_DEVELOPMENT.md) for the exact local workflows.

On the configured development computer, double-click `Run-ClearDictate-Developer-Preview.cmd` to open the Windows microphone and text-pipeline harness. This launcher is a convenience for the existing Debug toolchain, not a redistributable installer.

ClearDictate source is licensed under the [Apache License 2.0](LICENSE). See [third-party notices](THIRD_PARTY_NOTICES.md) for pinned dependencies and models.
