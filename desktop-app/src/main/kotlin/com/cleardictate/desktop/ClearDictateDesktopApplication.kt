package com.cleardictate.desktop

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import java.awt.EventQueue
import java.awt.Frame
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent

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
private fun runClearDictateDesktopApplication()
{
    val runtimeReadiness = DesktopRuntimeConfigurationLocator().locate()
    val runtimeConfiguration = (runtimeReadiness as? DesktopRuntimeReadiness.Ready)?.configuration
    val spokenFormattingRuleStore = SqliteDesktopSpokenFormattingRuleStore.openDefault()
    val transcriptProcessor = DesktopTranscriptProcessor(runtimeConfiguration, spokenFormattingRules = spokenFormattingRuleStore::currentRules)
    val speechRecorder = DesktopSpeechRecorder(runtimeConfiguration)
    val speechTranscriber = QwenDesktopSpeechTranscriber(runtimeConfiguration)
    val dictationHistory = SqliteDesktopDictationHistory.openDefault()
    val dictationPipeline = DesktopDictationPipeline(speechRecorder, speechTranscriber, transcriptProcessor, dictationHistory)
    val phoneAccessConfiguration = DesktopPhoneAccessConfiguration.loadOrCreate()
    val phoneServer = DesktopRemoteDictationServer(
        bindAddress = phoneAccessConfiguration.bindAddress,
        authorizationToken = phoneAccessConfiguration.authorizationToken,
        initiallyReady = false,
        dictationProcessor = DesktopRemoteDictationProcessor {
            dictationPipeline.openRemoteDictation()
        }
    )
    phoneServer.start()

    try
    {
        application {
            var historyVisible by remember { mutableStateOf(false) }
            var rulesVisible by remember { mutableStateOf(false) }
            val mainWindowState = remember { WindowState(width = 600.dp, height = 370.dp) }
            val trayWindowController = remember { DesktopTrayWindowController() }
            val trayIcon = remember { DesktopTrayIconPainter() }
            val startupRegistration = remember { WindowsDesktopStartupRegistration.forCurrentProcess() }
            val showMainWindow = trayWindowController::restore

            Tray(
                icon = trayIcon,
                tooltip = "ClearDictate",
                onAction = showMainWindow
            ) {
                Item("Open ClearDictate", onClick = showMainWindow)
                Separator()
                Item("Exit", onClick = ::exitApplication)
            }

            Window(
                onCloseRequest = ::exitApplication,
                title = "ClearDictate",
                state = mainWindowState
            ) {
                SideEffect {
                    trayWindowController.attach(window, mainWindowState)
                }
                DisposableEffect(window) {
                    val minimizeListener = object : WindowAdapter() {
                        override fun windowIconified(event: WindowEvent)
                        {
                            trayWindowController.minimizeToTray()
                        }
                    }
                    window.addWindowListener(minimizeListener)
                    onDispose {
                        window.removeWindowListener(minimizeListener)
                        trayWindowController.detach(window)
                    }
                }
                ClearDictateDesktopPreviewScreen(
                    runtimeReadiness,
                    speechRecorder,
                    dictationPipeline,
                    phoneAccessConfiguration,
                    phoneServer,
                    startupRegistration,
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
    }
    finally
    {
        phoneServer.close()
        dictationPipeline.close()
    }
}

/**
 * Retains the native Compose window behind stable tray callbacks and coordinates transitions after the current Windows event finishes.
 */
private class DesktopTrayWindowController
{
    private var window: ComposeWindow? = null
    private var state: WindowState? = null

    /**
     * Associates the controller with the current Compose window after it has been created.
     */
    fun attach(window: ComposeWindow, state: WindowState)
    {
        this.window = window
        this.state = state
    }

    /**
     * Discards a window only when it is still the active attachment.
     */
    fun detach(window: ComposeWindow)
    {
        if (this.window === window)
        {
            this.window = null
            state = null
        }
    }

    /**
     * Hides an iconified window after Windows completes its minimize event so no taskbar button remains.
     */
    fun minimizeToTray()
    {
        val retainedWindow = window ?: return
        val retainedState = state ?: return
        EventQueue.invokeLater {
            if (window !== retainedWindow)
            {
                return@invokeLater
            }
            retainedWindow.isVisible = false
            retainedWindow.extendedState = retainedWindow.extendedState and Frame.ICONIFIED.inv()
            retainedState.isMinimized = false
        }
    }

    /**
     * Restores the retained native window without reconstructing the model pipeline or phone server.
     */
    fun restore()
    {
        val retainedWindow = window ?: return
        val retainedState = state ?: return
        EventQueue.invokeLater {
            if (window !== retainedWindow)
            {
                return@invokeLater
            }
            retainedWindow.extendedState = retainedWindow.extendedState and Frame.ICONIFIED.inv()
            retainedState.isMinimized = false
            retainedWindow.isVisible = true
            retainedWindow.toFront()
            retainedWindow.requestFocus()
        }
    }
}
