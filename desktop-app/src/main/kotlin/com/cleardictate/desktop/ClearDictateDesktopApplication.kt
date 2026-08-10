package com.cleardictate.desktop

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application

/**
 * Starts the Windows push-to-talk application and owns its local worker pipeline.
 */
fun main() = application {
    val runtimeReadiness = remember { DesktopRuntimeConfigurationLocator().locate() }
    val runtimeConfiguration = (runtimeReadiness as? DesktopRuntimeReadiness.Ready)?.configuration
    val transcriptProcessor = remember(runtimeConfiguration) { DesktopTranscriptProcessor(runtimeConfiguration) }
    val speechRecorder = remember(runtimeConfiguration) { DesktopSpeechRecorder(runtimeConfiguration) }
    val speechTranscriber = remember(runtimeConfiguration) { QwenDesktopSpeechTranscriber(runtimeConfiguration) }
    val dictationHistory = remember { SqliteDesktopDictationHistory.openDefault() }
    val dictationPipeline = remember(speechRecorder, speechTranscriber, transcriptProcessor, dictationHistory) {
        DesktopDictationPipeline(speechRecorder, speechTranscriber, transcriptProcessor, dictationHistory)
    }
    val phoneAccessConfiguration = remember { DesktopPhoneAccessConfiguration.loadOrCreate() }
    val phoneServer = remember(dictationPipeline, phoneAccessConfiguration) {
        DesktopRemoteDictationServer(
            bindAddress = phoneAccessConfiguration.bindAddress,
            authorizationToken = phoneAccessConfiguration.authorizationToken,
            dictationProcessor = DesktopRemoteDictationProcessor { audio ->
                dictationPipeline.processRemoteDictation(audio)
            }
        )
    }

    DisposableEffect(dictationPipeline, phoneServer) {
        onDispose {
            phoneServer.close()
            dictationPipeline.close()
        }
    }

    Window(
        onCloseRequest = ::exitApplication,
        title = "ClearDictate",
        state = WindowState(width = 600.dp, height = 440.dp)
    ) {
        ClearDictateDesktopPreviewScreen(runtimeReadiness, speechRecorder, dictationPipeline, phoneAccessConfiguration, phoneServer)
    }
}
