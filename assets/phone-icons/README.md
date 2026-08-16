# ClearDictate phone icons

These are editable source copies of every custom icon currently used by the Android app. Android system and keyboard-owned symbols are not ClearDictate assets.

| Editable source | Used for | Deployed Android resource |
| --- | --- | --- |
| `floating-microphone.png` | Connected, available, and recording floating microphone | `android-app/src/main/res/drawable-nodpi/tidal_microphone_green.png` |
| `microphone-white.xml` | App launcher icon and foreground-service notification | `inference-service/src/main/res/drawable/ic_cleardictate_microphone.xml` |
| `no-entry.xml` | Disconnected floating control | `android-app/src/main/res/drawable/ic_cleardictate_no_entry.xml` |
| `undo-compact-orange.png` | Remove-last-dictation floating control | `android-app/src/main/res/drawable-nodpi/undo_compact_orange.png` |
| `processing-hourglass-source.gif` | Original 16-frame processing animation | `android-app/src/main/res/drawable-nodpi/processing_hourglass_green.webp` |
| `processing-hourglass.png` | High-resolution still artwork for the processing animation | Not deployed directly |
| `processing-hourglass.webp` | Editable-folder copy of the optimized Android animation | `android-app/src/main/res/drawable-nodpi/processing_hourglass_green.webp` |

The app does not build directly from this folder. After editing an icon, copy or export it to the deployed resource listed above. Deployed raster icons use a 192 x 192 canvas. The processing WebP preserves the GIF's 16 frames, 70 ms frame delay and continuous loop while converting its edge-connected black preview canvas to transparency.
