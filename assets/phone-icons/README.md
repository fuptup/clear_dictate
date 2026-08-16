# ClearDictate phone icons

These are editable source copies of every custom icon currently used by the Android app. Android system and keyboard-owned symbols are not ClearDictate assets.

| Editable source | Used for | Deployed Android resource |
| --- | --- | --- |
| `floating-microphone.png` | Connected, available, and recording floating microphone | `android-app/src/main/res/drawable-nodpi/tidal_microphone_green.png` |
| `microphone-white.xml` | App launcher icon and foreground-service notification | `inference-service/src/main/res/drawable/ic_cleardictate_microphone.xml` |
| `no-entry.xml` | Disconnected floating control | `android-app/src/main/res/drawable/ic_cleardictate_no_entry.xml` |
| `undo.xml` | Remove-last-dictation floating control | `android-app/src/main/res/drawable/ic_cleardictate_undo.xml` |

The app does not build directly from this folder. After editing an icon, copy or export it to the deployed resource listed above. The deployed floating microphone is a 192 x 192 PNG derived from the full-resolution source.
