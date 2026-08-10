# Android development

ClearDictate's Android Debug application contains a standalone recorder, a floating accessibility microphone, an optional system input method, and an isolated
foreground microphone service. The phone does not download or run speech or text models. It retains one QR-scanned or manually entered PC address and bearer token in
application-private storage.

Recording uses 16 kHz mono PCM16 audio. While either control is held, ClearDictate shows live microphone energy. Release stops capture, uploads the completed in-memory
recording to the paired Windows application, and shows an indeterminate processing indicator. The PC returns only the polished transcript. The floating control inserts
that text into the same supported non-sensitive editor while leaving the selected keyboard unchanged. Cancellation closes an in-flight request and all owned audio arrays
are overwritten after terminal handling.

## Build

Build only the Debug variant while the private-network HTTP transport is in use:

```powershell
$env:JAVA_HOME='C:\Program Files\Unity\Hub\Editor\6000.4.8f1\Editor\Data\PlaybackEngines\AndroidPlayer\OpenJDK'
$env:ANDROID_HOME='C:\Program Files\Unity\Hub\Editor\6000.4.8f1\Editor\Data\PlaybackEngines\AndroidPlayer\SDK'
.\gradlew.bat :android-app:assembleDebug
```

The APK is written to `android-app/build/outputs/apk/debug`.

With one authorized Android device or emulator connected, the repository helper performs the build, install, and launch:

```powershell
.\tools\android\Install-ClearDictate-Debug.cmd
```

Pass `-Serial <adb-serial>` when more than one device is connected.

## Project emulator

The configured development computer has an Android 15 Google APIs emulator under `.tooling/android-avd`. Start its visible form for UI and host-microphone testing:

```powershell
.\tools\android\Start-ClearDictate-Emulator.cmd
```

Use `-Headless` for deterministic UI automation without host audio. The helper reuses the installed virtual device and waits until Android reports a completed boot.

## Pair and test

Use the [remote connectivity runbook](REMOTE_CLIENT_CONNECTIVITY.md) for the complete Tailscale, permissions, firewall, verification, and reconnection procedure.

1. Start the Windows ClearDictate preview and wait for both models to report Ready.
2. Connect Tailscale on both devices. When the PC is shared with an external tester, the tester accepts that machine share from their own Tailscale account.
3. Select Phone in the Windows application and use the advertised Tailscale address. A private-LAN address is suitable only on a trusted local network.
4. Install and open the Debug APK, select **Scan QR**, and scan the code shown on the PC. ClearDictate verifies and saves the pairing automatically.
5. If Google Play services cannot provide the scanner, enter the displayed address and token and select **Connect manually**.
6. Grant recording permissions and test the standalone recorder.
7. Select **Enable floating microphone**, open **ClearDictate floating dictation** in Android Accessibility settings, and explicitly enable it.
8. Keep the preferred keyboard selected. Focus a supported text field in any application, hold the floating microphone, speak, and release. The button turns red while
   recording, shows a processing spinner after release, and inserts the PC-polished text when the same field remains focused.
9. The ClearDictate keyboard remains optional. It provides explicit transcript review before insertion and a **Retry PC** action when the PC was initially offline.

No USB connection is required for dictation. Android Debug Bridge over USB is useful only for installation, logs, and diagnostics.

## Required physical validation

Before calling Android input production-ready, validate Android 14, 15, and 16 devices with Gboard and other installed keyboards over multiple applications. Exercise
the floating accessibility control and optional input method across standard Android, Compose, browser, and custom editors; microphone routing; notification permission
revocation; screen lock; process death; editor changes; cancellation during upload; long recordings; poor Wi-Fi; and PC shutdown. Measure cold and warm end-to-end
latency and confirm that password, personal identification number, payment-card, and one-time-code fields remain blocked.

The accessibility service is explicitly user-enabled. It queries only focused editable nodes, retains only a field identity while recording, and reads current editor text
transiently when Android requires a complete `ACTION_SET_TEXT` replacement. It does not capture screenshots or send editor contents or application identity to the PC.
Unsupported custom editors and fields that change before the PC response are rejected without insertion.

The current bearer-token application protocol is authenticated HTTP. Use it through the verified Tailscale tunnel or on a trusted private LAN; never expose TCP 8765
through router port forwarding. A production release still requires an explicit encrypted transport design compatible with the release manifest.
