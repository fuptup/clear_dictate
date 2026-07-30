# ClearDictate privacy behavior

ClearDictate performs speech recognition and transcript processing on the device. It does not send
microphone audio, transcripts, target-application identity, or editor contents to a server.

Network access is used only for an explicit model download that starts from the pinned secure
addresses and revisions listed in the model manifest. Secure redirects used by the model host are
allowed. Every model component is checked for exact filename, byte count, and Secure Hash
Algorithm 256-bit digest before native code can open it.

The Android input method disables dictation for password, personal identification number, and other
sensitive editor types. It does not inspect surrounding text in private editors. Private-editor
transcripts are not added to undo state or history and are cleared after insertion, rejection,
editor changes, cancellation, and keyboard lifecycle changes. Reusable microphone buffers are
overwritten with zeroes as they leave the recognition path and again at session teardown.

ClearDictate does not currently implement transcript history on Android. When history is added, it
must remain local, user-controlled, and disabled for private editor sessions.

The current Android and Windows outputs are Debug development artifacts. Device validation is still
required for operating-system microphone restrictions, audio-route changes, permission revocation,
screen lock, native-process death, and long-duration memory behavior.
