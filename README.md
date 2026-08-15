# ClearDictate

ClearDictate is a private-network dictation project targeting Android and Windows. The Android recorder and system keyboard capture speech, then the Windows
application runs speech recognition and text polishing on the PC's NVIDIA GPU.

Current implemented capabilities, with verification boundaries listed below:

- deterministic Raw and Clean transcript processing;
- protected-value validation and fail-closed Polished fallback;
- an Android standalone recorder, optional system Input Method Editor, and floating accessibility microphone that works beside the selected keyboard;
- QR or manual PC pairing, explicit PC reconnect, foreground microphone capture, live input level, processing feedback, focused-field insertion, and sensitive-field blocking;
- a bounded, versioned Kotlin/C++ worker protocol;
- a persistent CUDA Windows text worker using Qwen3.5 9B Q6_K through pinned llama.cpp;
- cryptographic model verification before same-handle loading;
- a synchronous, drained cancellation contract in the Windows worker and asynchronous cancellation fencing on Android;
- a real Kotlin-to-worker integration test through the semantic-safety pipeline;
- a Windows push-to-talk preview with compact microphone selection, WSL/vLLM Qwen3-ASR 1.7B recognition, automatic Qwen3.5 polishing, editable polished output, and clipboard copying;
- a local SQLite dictation history that retains completed audio as WAV, both model outputs, UTC capture time, and each PC pipeline stage duration, with a date-filtered Windows viewer and click-to-play rows;
- a bounded framed PCM16 phone protocol, authenticated Windows endpoint, and Android client that streams audio into a stateful PC ASR session before release, then polishes once.

Important current limitations:

- the Windows push-to-talk path is connected through isolated local workers, but a Windows system-wide overlay, global shortcut, and target-application insertion are not implemented yet;
- Android history, full settings/profile screens, a hybrid typing keyboard, and measured phone performance remain incomplete;
- keyboard microphone eligibility still needs physical validation on Android 14, 15, and 16 while the host application is foregrounded;
- the Windows executable is a Debug development artifact and depends on Microsoft Debug runtimes; it is not a distributable installer;
- measured tests on the target Motorola Edge+ phones have not yet been performed;
- the Android/PC application protocol uses authenticated HTTP in Debug builds and currently relies on a verified Tailscale tunnel or a trusted private LAN for transport
  encryption and isolation; the production manifest still requires an explicit release transport design.

See the [remote connectivity runbook](docs/REMOTE_CLIENT_CONNECTIVITY.md), [Windows development instructions](docs/WINDOWS_DEVELOPMENT.md), and
[Android development instructions](docs/ANDROID_DEVELOPMENT.md) for the exact local workflows.

On the configured development computer, double-click `Run-ClearDictate-Developer-Preview.cmd` to open the Windows microphone and text-pipeline harness. This launcher is a convenience for the existing Debug toolchain, not a redistributable installer.

ClearDictate source is licensed under the [Apache License 2.0](LICENSE). See [third-party notices](THIRD_PARTY_NOTICES.md) for pinned dependencies and models.
