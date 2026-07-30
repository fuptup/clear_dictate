# ClearDictate adds targeted native-binding rules alongside the pinned native integrations.
# Moonshine's pinned native library uses name-based Java Native Interface entry points and
# FindClass lookups. Renaming these classes would break native linking and transcript marshalling.
-keep class ai.moonshine.voice.** { *; }
