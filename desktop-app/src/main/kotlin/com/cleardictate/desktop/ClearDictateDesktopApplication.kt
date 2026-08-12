package com.cleardictate.desktop

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application

/**
 * Acquires process-wide ownership before Compose or either model worker can be initialized.
 */
fun main()
{
    val instanceLease = DesktopSingleInstanceController(WindowsDesktopSingleInstancePlatform()).acquireOrActivate() ?: return
    instanceLease.use {
        runClearDictateDesktopApplication()
    }
}

/**
 * Starts the Windows push-to-talk application and owns its local worker pipeline after single-instance ownership is established.
 */
private fun runClearDictateDesktopApplication() = application {
    val runtimeReadiness = remember { DesktopRuntimeConfigurationLocator().locate() }
    val runtimeConfiguration = (runtimeReadiness as? DesktopRuntimeReadiness.Ready)?.configuration
    val spokenFormattingRuleStore = remember { SqliteDesktopSpokenFormattingRuleStore.openDefault() }
    val transcriptProcessor = remember(runtimeConfiguration, spokenFormattingRuleStore) {
        DesktopTranscriptProcessor(runtimeConfiguration, spokenFormattingRules = spokenFormattingRuleStore::currentRules)
    }
    val speechRecorder = remember(runtimeConfiguration) { DesktopSpeechRecorder(runtimeConfiguration) }
    val speechTranscriber = remember(runtimeConfiguration) { QwenDesktopSpeechTranscriber(runtimeConfiguration) }
    val dictationHistory = remember { SqliteDesktopDictationHistory.openDefault() }
    var historyVisible by remember { mutableStateOf(false) }
    var rulesVisible by remember { mutableStateOf(false) }
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
        state = WindowState(width = 600.dp, height = 370.dp)
    ) {
        ClearDictateDesktopPreviewScreen(
            runtimeReadiness,
            speechRecorder,
            dictationPipeline,
            phoneAccessConfiguration,
            phoneServer,
            onOpenHistory = { historyVisible = true },
            onOpenRules = { rulesVisible = true }
        )
    }

    if (historyVisible)
    {
        Window(
            onCloseRequest = { historyVisible = false },
            title = "History",
            state = WindowState(width = 1_160.dp, height = 620.dp)
        ) {
            ClearDictateHistoryScreen(dictationHistory)
        }
    }

    if (rulesVisible)
    {
        Window(
            onCloseRequest = { rulesVisible = false },
            title = "Rules",
            state = WindowState(width = 740.dp, height = 540.dp)
        ) {
            ClearDictateRulesScreen(spokenFormattingRuleStore)
        }
    }
}
