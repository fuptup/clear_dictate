# Remote client connectivity runbook

This is the living setup and recovery record for connecting ClearDictate clients to the GPU inference server. Update it whenever pairing, authentication, network
transport, permissions, background execution, firewall behavior, or reconnection behavior changes.

Last physically verified: 2026-08-10 on a Motorola Edge+ (2022), Android 12, Tailscale 1.102.2, Windows 10, and an NVIDIA RTX 3090.

## Current architecture

The Windows PC is the only inference server. Android records 16 kHz mono PCM16 audio, uploads the completed recording, and receives only the polished transcript.
Speech recognition and rewriting remain on the PC.

```mermaid
flowchart LR
    Phone["Android ClearDictate"] --> PhoneVpn["Tailscale Android VPN"]
    PhoneVpn -->|"encrypted WireGuard tunnel"| PcVpn["Tailscale on the server PC"]
    PcVpn -->|"TCP 8765"| Server["Authenticated ClearDictate HTTP service"]
    Server --> Asr["Qwen3-ASR 1.7B"]
    Asr --> Rewrite["Qwen3.5 9B rewriter"]
    Rewrite --> Phone
```

The application protocol currently uses HTTP with a bearer token. Tailscale supplies transport encryption. Never port-forward TCP 8765 or send the token or dictated
audio directly across an untrusted network.

When Tailscale is active on the PC, ClearDictate advertises and binds only its address in Tailscale's `100.64.0.0/10` range. It does not listen on the PC's Ethernet or
Wi-Fi address. Without a Tailscale interface, the current development build falls back to private-LAN pairing.

## PC setup

1. Install the official Tailscale Windows client. This requires Windows administrator approval.
2. Sign in to the server PC's Tailscale account and confirm that the machine is connected.
3. Start ClearDictate with `Run-ClearDictate-Developer-Preview.cmd` and wait for both persistent models to load.
4. Confirm that ClearDictate listens on the PC's Tailscale address on TCP 8765. It must not listen on `0.0.0.0`, `::`, or the public-network adapter when Tailscale is
   available.
5. Restrict Windows Firewall access to TCP 8765 from `100.64.0.0/10`. Firewall changes require administrator approval. Do not create a public, any-port application rule.
6. Keep the PC awake, connected to the internet, signed in to Tailscale, and running ClearDictate while remote clients are expected to work.

For external testers, share only the server PC through Tailscale machine sharing. Do not add friends to the whole tailnet unless broader access is intentional. Each tester
uses an individual Tailscale account and accepts the shared-machine invitation.

## Android setup

1. Install Tailscale from Google Play or a checksum-verified official release.
2. Sign in with the tester's own identity and accept the shared server-PC invitation.
3. Approve Android's VPN connection prompt and leave Tailscale connected. Android permits only one active VPN, so another VPN can displace Tailscale.
4. Allow Tailscale notifications. They can report authentication expiry and connection problems.
5. Set Tailscale battery use to **Unrestricted** on devices with aggressive background management. On the development Motorola this was also applied over Android Debug
   Bridge as a device-idle whitelist entry. That ADB setting is local to that phone and is not transferred by the ClearDictate APK.
6. Install the signed ClearDictate APK. Sideloading requires the user to approve the installer as an allowed source. A Play closed-testing release avoids this extra step.
7. Open ClearDictate and grant microphone permission.
8. Pair by scanning the QR code shown by the PC, or enter the PC's Tailscale URL and pairing token manually. ClearDictate verifies the authenticated health endpoint before
   saving both values in application-private preferences.
9. Select **Accessibility settings**, open **ClearDictate floating dictation**, and explicitly enable **Use service**. Android does not permit the app or installer to grant
   this permission silently.
10. Leave the accessibility shortcut disabled unless the tester wants Android's optional volume-key or accessibility-button shortcut. The service itself must remain
    enabled.
11. Keep the tester's preferred keyboard selected. The ClearDictate keyboard is optional; the floating microphone is designed to work beside another keyboard.

USB is not part of normal dictation. It is used only for development installation, private-preference inspection, logs, screenshots, and diagnostics.

## Required permissions and approvals

| Component | Permission or approval | Why it is required | Can setup grant it silently? |
| --- | --- | --- | --- |
| Windows | Administrator approval for Tailscale | Installs the VPN service and virtual network interface | No |
| Windows | Administrator approval for a scoped firewall rule | Allows only Tailscale peers to reach TCP 8765 | No |
| Android | APK installation approval | Installs a sideloaded development build | No, except on managed devices |
| Android | Tailscale VPN consent | Creates the encrypted network interface | No |
| Android | Tailscale sign-in | Gives the phone its own device identity | No |
| Android | Tailscale notifications | Reports authentication and connection state | User-controlled |
| Android | Tailscale unrestricted battery use | Reduces background suspension during screen-off and network changes | User-controlled without device management |
| Android | Microphone permission | Records the held push-to-talk utterance | No |
| Android | Accessibility service | Displays the overlay and inserts text into the focused supported field | No |
| Android | Accessibility shortcut | Provides an optional system shortcut to toggle the service | Not required |
| Android | ClearDictate input method | Provides the optional ClearDictate keyboard | Not required |

## Pairing and authentication

The PC stores one cryptographically random bearer token in Java Preferences. The Android app stores the paired base URL and token in its private application storage. The
token is masked in the user interface and should never be copied into logs, screenshots, issues, or chat messages.

The current server has one shared token rather than one independently revocable credential per phone. This is acceptable only for the present single-user development
setup. A tester release requires per-device tokens, device listing, individual revocation, rate limits, and a bounded inference queue.

## Connection lifecycle

For normal operation:

1. The PC boots and Tailscale connects.
2. ClearDictate starts, loads both GPU workers, detects the Tailscale address, and binds TCP 8765 only to that address. It then warms the GPU paths in the background
   with local synthetic silence and a fixed trusted phrase. The endpoint remains available while warm-up runs, so it cannot block phone reconnection.
3. The phone's Tailscale VPN connects over Wi-Fi or mobile data.
4. ClearDictate's Android inference service checks the authenticated health endpoint.
5. The floating microphone becomes available in supported, non-sensitive text fields.
6. Holding the control records locally. Releasing it uploads the complete audio, waits for the serialized PC pipeline, and inserts the returned text only if the same field
   remains focused.

The desktop **Phone** dialog displays the queue, ASR, rewriting, and total PC-pipeline durations for the most recent successful phone dictation. These values contain no
audio, endpoint, token, or transcript data and are intended to distinguish model latency from network latency.

## Verification procedure

Perform these checks without exposing the bearer token:

1. Confirm both devices appear connected in Tailscale.
2. Confirm the PC listener's local address is the Tailscale address and its port is 8765.
3. From the phone, confirm the PC's Tailscale address responds.
4. Request `/v1/health` without authorization and expect `401 Unauthorized`.
5. Request `/v1/health` using the token retained inside the Android app's private storage and expect `200 OK`.
6. Confirm that the same request to the PC's LAN address is unreachable while the server is bound to Tailscale.
7. Disable phone Wi-Fi temporarily, allow Tailscale to move to mobile data, and repeat the authenticated health check. Restore Wi-Fi immediately afterward.
8. Open ClearDictate and confirm **PC connected**, **PC service: Connected**, **Microphone: Allowed**, and **Floating microphone: Enabled**.
9. Focus a harmless text field in another application and confirm the microphone is enabled, records, processes, and inserts the result.

The development setup has passed the unauthorized `401`, authenticated `200`, blocked-LAN, and authenticated mobile-data checks. The mobile-data check used only a health
request and restored Wi-Fi afterward.

## Reconnection and recovery

The floating microphone is grey while the Android inference client is disconnected, the PC model is not ready, or a recording error is awaiting recovery. The message
**ClearDictate is reconnecting to the paired PC** describes this combined unavailable state; it does not by itself prove that the Tailscale route has failed.

The accessibility service and the main ClearDictate screen are separate clients of the Android inference process. If that process restarts, Android reconnects both clients.
On reconnection each client now clears any abandoned recording error, returns to idle, and waits for the inference process to replay current PC model readiness. This lets
the long-lived floating microphone recover without toggling the accessibility service or reopening ClearDictate.

A failed or accidentally too-short dictation now returns the floating microphone to idle while retaining the failure message for diagnosis. It no longer changes the
control into the same unavailable state used for connection and model failures, so the next press can retry immediately.

Completed text is fenced to the editor that was focused when recording began. Identified Android fields may resize while the PC processes the utterance without being
mistaken for a different field; the fence still requires the same window, application, widget class, and view ID. Editors without a view ID must retain overlapping bounds.
When an editor exposes its cursor, ClearDictate uses Android's direct set-text action with explicit selection and spacing. Some editors, including the verified WhatsApp
composer, hide cursor and hint metadata but expose Android's native paste action. ClearDictate uses native paste for those editors so Android inserts at the real cursor and
does not mistake a visual placeholder such as **Message** for draft text. Because Android's paste action accepts no text argument, ClearDictate marks the transcript as
sensitive, places it on the system clipboard only for the synchronous paste action, and immediately restores the preceding clipboard. This path requires text-change event
access to verify the actual inserted range and apply boundary spacing.

On the verified Motorola/WhatsApp combination, native paste inserted dictated text into an empty composer without the visual **Message** placeholder becoming content, and
the adjacent undo control removed only that dictated insertion.

After successful insertion, a smaller undo icon appears beside the microphone. ClearDictate retains only the field identity, inserted range, and a SHA-256 digest of the
inserted segment. The icon removes that segment only while the same field still contains it unchanged, preserving all text that existed before insertion and any later text
outside the inserted range. The undo record and icon are cleared after use or when focus moves to another editor.

Check in this order:

1. Confirm the Windows PC is powered on and the ClearDictate preview is running.
2. Confirm the PC listener is present on its Tailscale address and that both GPU workers are ready.
3. Open Tailscale on the phone. If it claims to be connected but traffic is stale, bringing the app to the foreground can reactivate its engine after a Wi-Fi/mobile-data
   transition.
4. Confirm no other Android VPN has displaced Tailscale.
5. Confirm Tailscale has unrestricted battery use and is not suspended by Motorola or another manufacturer's battery manager.
6. Reopen ClearDictate. Its service should recheck the saved endpoint and update the overlay state.
7. If connectivity still fails, run the unauthenticated and authenticated health checks separately to distinguish routing from credential failure.

If the main ClearDictate screen can record but the floating microphone alone remains grey, verify that the accessibility service is enabled. A build older than the
2026-08-10 recovery fixes can retain a stale recording error after either an inference-process restart or a failed dictation and must be updated.

On 2026-08-10, the development phone remained grey after moving outdoors and returning to Wi-Fi. The PC listener, saved endpoint, and token were correct. The Tailscale
Android VPN engine was stale; opening Tailscale restored the route and ClearDictate returned `200 OK`. Tailscale was then added to the phone's device-idle whitelist.

## Known limitations

- ClearDictate cannot silently install, sign in to, authorize, or restart Tailscale. The app should eventually detect this specific failure and provide an **Open
  Tailscale** recovery action.
- The Debug APK permits cleartext HTTP because encryption is supplied by Tailscale. The production manifest currently blocks cleartext traffic, so a release build needs
  an explicitly designed encrypted endpoint or narrowly scoped transport policy before this route can ship.
- The external Tailscale service has its own pricing and commercial-use terms. It is a development and invited-beta transport, not yet a permanent commercial dependency.
- The PC currently processes only one inference job at a time, has two HTTP request threads, and shares one bearer token across clients. It is not yet a safe multi-client
  service.
- Obsolete broad Windows Firewall rules from an earlier packaged ClearDictate build were observed on the development PC. Removing them and installing the scoped
  Tailscale-only replacement still requires an administrator-approved cleanup. The current server mitigates this by binding only to the Tailscale interface.
- Captive portals and competing VPNs can interrupt Tailscale. Always authenticate to a trusted captive portal before reconnecting the VPN.
- Cursor-hidden editors require a brief clipboard round trip for native paste. Android, the focused app, the active keyboard, or vendor clipboard-history features may be
  able to observe that transient value despite the sensitive marker; the previous clipboard is restored immediately after the paste action.

## Documentation maintenance checklist

Update this file in the same change whenever development alters any of the following:

- the server port, bind-address policy, protocol path, timeout, or request format;
- pairing codes, token storage, per-device identity, revocation, or Tailscale sharing;
- Android permissions, accessibility behavior, foreground services, battery handling, or VPN recovery;
- Windows installation, startup, firewall, model readiness, or service supervision;
- production encryption or release-manifest network policy;
- verified device, Android version, network-transition behavior, or troubleshooting evidence.
