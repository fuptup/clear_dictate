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

Each successful desktop or Android dictation is retained locally on the Windows server in
`%LOCALAPPDATA%\ClearDictate\dictation-history.sqlite`. Each record contains the complete mono
PCM16 WAV audio, the Qwen3-ASR transcript, the Qwen3.5 polished text, UTC capture datetime, and
queue, recognition, rewriting, and total PC-pipeline durations. The database is not uploaded or
shared with Android clients. This retention is intended for local review and preparing explicit
fine-tuning data; it applies to every successful dictation because the PC pipeline does not receive
the Android editor's sensitivity classification.

The current Android and Windows outputs are Debug development artifacts. Device validation is still
required for operating-system microphone restrictions, audio-route changes, permission revocation,
screen lock, native-process death, and long-duration memory behavior.
