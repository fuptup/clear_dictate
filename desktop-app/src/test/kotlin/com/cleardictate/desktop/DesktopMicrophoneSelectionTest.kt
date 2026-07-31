package com.cleardictate.desktop

import com.cleardictate.desktop.inference.WindowsCaptureDevice
import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopMicrophoneSelectionTest
{
    @Test
    fun `dropdown options keep system default first and label the resolved Windows default`()
    {
        val devices = listOf(
            WindowsCaptureDevice("opaque-webcam-id", "Webcam microphone", isDefault = true),
            WindowsCaptureDevice("opaque-headset-id", "Headset microphone", isDefault = false)
        )

        assertEquals(
            listOf(
                DesktopMicrophoneOption("", "System default"),
                DesktopMicrophoneOption("opaque-webcam-id", "Webcam microphone (Windows default)"),
                DesktopMicrophoneOption("opaque-headset-id", "Headset microphone")
            ),
            buildDesktopMicrophoneOptions(devices)
        )
    }
}
