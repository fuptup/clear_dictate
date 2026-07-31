package com.cleardictate.desktop

import com.cleardictate.desktop.inference.WindowsCaptureDevice

/**
 * One compact microphone choice shown by the desktop preview.
 * The endpoint identifier remains opaque and is never substituted with the display label.
 */
data class DesktopMicrophoneOption(
    val endpointIdentifier: String,
    val displayLabel: String
)

fun buildDesktopMicrophoneOptions(captureDevices: List<WindowsCaptureDevice>): List<DesktopMicrophoneOption>
{
    return buildList(captureDevices.size + 1) {
        add(DesktopMicrophoneOption(endpointIdentifier = "", displayLabel = "System default"))
        captureDevices.forEach { captureDevice ->
            val defaultMarker = if (captureDevice.isDefault) " (Windows default)" else ""
            add(
                DesktopMicrophoneOption(
                    endpointIdentifier = captureDevice.endpointIdentifier,
                    displayLabel = captureDevice.friendlyName + defaultMarker
                )
            )
        }
    }
}
