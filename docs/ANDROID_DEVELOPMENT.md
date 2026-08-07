# Android development

ClearDictate's Android Debug application contains a standalone recorder, a system input method, and an isolated foreground microphone service. The phone does not
download or run speech or text models. It retains one manually paired PC address and bearer token in application-private storage.

Recording uses 16 kHz mono PCM16 audio. While the control is held, the keyboard shows live microphone energy. Release stops capture, uploads the completed in-memory
recording to the paired Windows application, and shows an indeterminate processing indicator. The PC returns only the polished transcript. Cancellation closes an
in-flight request and all owned audio arrays are overwritten after terminal handling.

## Build

Build only the Debug variant while the private-network HTTP transport is in use:

```powershell
$env:JAVA_HOME='C:\Program Files\Unity\Hub\Editor\6000.4.8f1\Editor\Data\PlaybackEngines\AndroidPlayer\OpenJDK'
$env:ANDROID_HOME='C:\Program Files\Unity\Hub\Editor\6000.4.8f1\Editor\Data\PlaybackEngines\AndroidPlayer\SDK'
.\gradlew.bat :android-app:assembleDebug
```

The APK is written to `android-app/build/outputs/apk/debug`.

## Pair and test

1. Start the Windows ClearDictate preview and wait for both models to report Ready.
2. Select Phone in the Windows application and copy the displayed private-network address and token.
3. Connect the Android phone and PC to the same trusted private network.
4. Install and open the Debug APK, enter the address and token, then select Connect.
5. Grant recording permissions. Test the standalone recorder before enabling and selecting the ClearDictate keyboard.
6. In the keyboard, hold Hold to talk, speak, and release. Review the PC-polished transcript and select Insert.

No USB connection is required for dictation. Android Debug Bridge over USB is useful only for installation, logs, and diagnostics.

## Required physical validation

Before calling the keyboard production-ready, validate Android 14, 15, and 16 devices with the input method displayed over other applications. Exercise microphone
routing, notification permission revocation, screen lock, process death, editor changes, cancellation during upload, long recordings, poor Wi-Fi, and PC shutdown.
Measure cold and warm end-to-end latency and confirm that sensitive fields remain blocked.

The current bearer-token transport is authenticated but not encrypted. Do not use it on public or untrusted networks. A production release requires encrypted,
certificate-authenticated pairing.
