package com.cleardictate.desktop

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application

/**
 * Starts the Windows dictation and text-pipeline developer preview.
 */
fun main() = application {
    val runtimeReadiness = remember { DesktopRuntimeConfigurationLocator().locate() }
    val runtimeConfiguration = (runtimeReadiness as? DesktopRuntimeReadiness.Ready)?.configuration
    val transcriptProcessor = remember(runtimeConfiguration) {
        DesktopTranscriptProcessor(runtimeConfiguration)
    }
    val speechRecorder = remember(runtimeConfiguration) {
        DesktopSpeechRecorder(runtimeConfiguration)
    }

    DisposableEffect(transcriptProcessor, speechRecorder) {
        onDispose {
            speechRecorder.close()
            transcriptProcessor.close()
        }
    }

    Window(
        onCloseRequest = ::exitApplication,
        title = "ClearDictate — Developer Preview",
        state = WindowState(width = 1120.dp, height = 820.dp)
    ) {
        ClearDictateDesktopPreviewScreen(
            runtimeReadiness = runtimeReadiness,
            speechRecorder = speechRecorder,
            transcriptProcessor = transcriptProcessor
        )
    }
}
