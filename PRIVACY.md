# ClearDictate privacy behavior

The Windows application performs speech recognition and transcript processing locally on the user's
PC. The Android application sends each explicitly recorded audio clip to that paired PC over the
trusted private network and receives the polished transcript. It does not send audio or text to a
third-party service.

Internet access on the PC is used for explicit model downloads from the pinned secure addresses and
revisions listed in the model manifest. Secure redirects used by the model host are allowed. Every
model component is checked for exact filename, byte count, and Secure Hash Algorithm 256-bit digest
before native code can open it.

The Android input method and floating accessibility microphone disable dictation for password,
personal identification number, payment-card, one-time-code, and other recognized sensitive editor
types. The accessibility service retains only the focused field's identity while recording. At
insertion time it reads the current text transiently because Android's accessibility API requires a
complete replacement value, then discards it. Editor contents and target-application identity are
never sent to the PC. The service does not capture screenshots.

Private-editor transcripts are not added to undo state or history and are cleared after insertion,
rejection, editor changes, cancellation, and keyboard lifecycle changes. Reusable microphone
buffers are overwritten with zeroes as they leave the recognition path and again at session
teardown.

ClearDictate does not currently implement transcript history on Android. When history is added, it
must remain local, user-controlled, and disabled for private editor sessions.

The current Android and Windows outputs are Debug development artifacts. Device validation is still
required for operating-system microphone restrictions, audio-route changes, permission revocation,
screen lock, native-process death, and long-duration memory behavior.
