package com.cleardictate.android

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.cleardictate.android.models.RequiredModelsDownloadScheduler
import com.cleardictate.android.models.RequiredModelsDownloadWorker
import com.cleardictate.domain.TranscriptMode
import com.cleardictate.inference.OperationPrivacy
import com.cleardictate.inference.service.ClientRecordingState
import com.cleardictate.inference.service.InferenceConnectionState
import com.cleardictate.inference.service.InferenceClientState
import com.cleardictate.inference.service.InferenceServiceClient
import com.cleardictate.inference.service.SpeechModelState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import java.util.Locale
import java.util.UUID

/**
 * Hosts setup, verified model installation, and standalone local dictation.
 */
class MainActivity : ComponentActivity()
{
    private lateinit var inferenceServiceClient: InferenceServiceClient

    override fun onCreate(savedInstanceState: Bundle?)
    {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        inferenceServiceClient = InferenceServiceClient(this)

        setContent {
            ClearDictateTheme {
                ClearDictateRecordingScreen(inferenceServiceClient)
            }
        }
    }

    override fun onStart()
    {
        super.onStart()
        inferenceServiceClient.bind()
    }

    override fun onStop()
    {
        inferenceServiceClient.cancelDictation()
        super.onStop()
    }

    override fun onDestroy()
    {
        inferenceServiceClient.close()
        super.onDestroy()
    }
}

@Composable
private fun ClearDictateTheme(content: @Composable () -> Unit)
{
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize(), content = content)
    }
}

/**
 * Displays the first functional standalone dictation surface.
 */
@Composable
private fun ClearDictateRecordingScreen(inferenceServiceClient: InferenceServiceClient)
{
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val clientState by inferenceServiceClient.state.collectAsStateWithLifecycle()
    val recordingPermissionState = remember(context) {
        RecordingPermissionState(context)
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        recordingPermissionState.refresh()
    }
    var selectedMode by rememberSaveable {
        mutableStateOf(TranscriptMode.CLEAN)
    }
    var showRawTranscript by rememberSaveable {
        mutableStateOf(true)
    }
    var editableTranscript by rememberSaveable {
        mutableStateOf("")
    }
    val workManager = remember(context) {
        WorkManager.getInstance(context)
    }
    var downloadWorkIdentifier by rememberSaveable {
        mutableStateOf(RequiredModelsDownloadScheduler.currentWorkIdentifier(context)?.toString())
    }
    val downloadWorkFlow = remember(workManager, downloadWorkIdentifier) {
        downloadWorkIdentifier?.let { identifier ->
            workManager.getWorkInfoByIdFlow(UUID.fromString(identifier))
        } ?: flowOf(null)
    }
    val currentDownload by downloadWorkFlow.collectAsStateWithLifecycle(initialValue = null)
    val recordingActive = clientState.recordingState == ClientRecordingState.PREPARING ||
        clientState.recordingState == ClientRecordingState.LISTENING ||
        clientState.recordingState == ClientRecordingState.SPEECH_DETECTED ||
        clientState.recordingState == ClientRecordingState.FINALIZING
    val recordingDuration = rememberRecordingDuration(recordingActive)

    DisposableEffect(lifecycleOwner, recordingPermissionState)
    {
        val permissionRefreshObserver = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME)
            {
                recordingPermissionState.refresh()
            }
        }
        lifecycleOwner.lifecycle.addObserver(permissionRefreshObserver)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(permissionRefreshObserver)
        }
    }

    LaunchedEffect(clientState.completedOperationIdentifier)
    {
        if (clientState.completedOperationIdentifier != null)
        {
            editableTranscript = clientState.selectedTranscript
        }
    }

    val speechModelBecameReady =
        currentDownload?.progress?.getBoolean(RequiredModelsDownloadWorker.PROGRESS_SPEECH_MODEL_READY, false) == true ||
        currentDownload?.outputData?.getBoolean(RequiredModelsDownloadWorker.OUTPUT_SPEECH_MODEL_READY, false) == true
    LaunchedEffect(currentDownload?.state, speechModelBecameReady)
    {
        if (currentDownload?.state == WorkInfo.State.SUCCEEDED || speechModelBecameReady)
        {
            inferenceServiceClient.retrySpeechModelPreparation()
        }
    }

    StandaloneRecordingContent(
        viewState = StandaloneRecordingViewState(
            clientState = clientState,
            microphonePermissionGranted = recordingPermissionState.microphonePermissionGranted,
            notificationPermissionGranted = recordingPermissionState.notificationPermissionGranted,
            currentDownload = currentDownload,
            recordingActive = recordingActive,
            recordingDuration = recordingDuration,
            selectedMode = selectedMode,
            showRawTranscript = showRawTranscript,
            editableTranscript = editableTranscript
        ),
        actions = StandaloneRecordingActions(
            downloadModels = {
                downloadWorkIdentifier = RequiredModelsDownloadScheduler.enqueue(context).toString()
            },
            cancelDownload = {
                RequiredModelsDownloadScheduler.cancel(context)
            },
            requestPermissions = {
                permissionLauncher.launch(recordingPermissionState.requiredPermissions())
            },
            openPermissionSettings = {
                context.startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        "package:${context.packageName}".toUri()
                    )
                )
            },
            enableKeyboard = {
                context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            },
            selectKeyboard = {
                context.getSystemService(InputMethodManager::class.java).showInputMethodPicker()
            },
            selectMode = { mode ->
                selectedMode = mode
            },
            recordOrStop = {
                if (recordingActive)
                {
                    inferenceServiceClient.stopDictation()
                }
                else
                {
                    editableTranscript = ""
                    inferenceServiceClient.startDictation(
                        transcriptMode = selectedMode,
                        privacy = OperationPrivacy.STANDARD
                    )
                }
            },
            cancelRecording = inferenceServiceClient::cancelDictation,
            setRawTranscriptVisible = { visible ->
                showRawTranscript = visible
            },
            editTranscript = { changedText ->
                editableTranscript = changedText
            },
            clearTranscript = {
                editableTranscript = ""
                inferenceServiceClient.clearCompletedTranscript()
            }
        )
    )
}

/**
 * Groups the values rendered together by the standalone recorder without owning application state.
 */
private data class StandaloneRecordingViewState(
    val clientState: InferenceClientState,
    val microphonePermissionGranted: Boolean,
    val notificationPermissionGranted: Boolean,
    val currentDownload: WorkInfo?,
    val recordingActive: Boolean,
    val recordingDuration: String,
    val selectedMode: TranscriptMode,
    val showRawTranscript: Boolean,
    val editableTranscript: String
)
{
    val recordingReady: Boolean
        get() = microphonePermissionGranted &&
            notificationPermissionGranted &&
            clientState.connectionState == InferenceConnectionState.CONNECTED &&
            clientState.speechModelState == SpeechModelState.READY
}

/**
 * Names the user actions available on the standalone recorder.
 */
private data class StandaloneRecordingActions(
    val downloadModels: () -> Unit,
    val cancelDownload: () -> Unit,
    val requestPermissions: () -> Unit,
    val openPermissionSettings: () -> Unit,
    val enableKeyboard: () -> Unit,
    val selectKeyboard: () -> Unit,
    val selectMode: (TranscriptMode) -> Unit,
    val recordOrStop: () -> Unit,
    val cancelRecording: () -> Unit,
    val setRawTranscriptVisible: (Boolean) -> Unit,
    val editTranscript: (String) -> Unit,
    val clearTranscript: () -> Unit
)

/**
 * Renders the standalone recorder as a readable sequence of independent sections.
 */
@Composable
private fun StandaloneRecordingContent(viewState: StandaloneRecordingViewState, actions: StandaloneRecordingActions)
{
    Scaffold(
        modifier = Modifier.safeDrawingPadding()
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("ClearDictate", style = MaterialTheme.typography.headlineLarge)
            Text(
                "Audio and transcript processing stay on this device. Network access is used only when you explicitly download model files.",
                style = MaterialTheme.typography.bodyMedium
            )
            ReadinessCard(
                connectionState = viewState.clientState.connectionState,
                speechModelState = viewState.clientState.speechModelState,
                microphonePermissionGranted = viewState.microphonePermissionGranted,
                notificationPermissionGranted = viewState.notificationPermissionGranted,
                currentDownload = viewState.currentDownload,
                onDownload = actions.downloadModels,
                onCancelDownload = actions.cancelDownload,
                onRequestPermission = actions.requestPermissions,
                onOpenPermissionSettings = actions.openPermissionSettings,
                onEnableKeyboard = actions.enableKeyboard,
                onSelectKeyboard = actions.selectKeyboard
            )
            TranscriptModeSelector(
                selectedMode = viewState.selectedMode,
                enabled = !viewState.recordingActive,
                onSelected = actions.selectMode
            )
            RecordingCard(
                clientState = viewState.clientState,
                recordingDuration = viewState.recordingDuration,
                recordEnabled = viewState.recordingReady,
                onRecordOrStop = actions.recordOrStop,
                onCancel = actions.cancelRecording
            )
            TranscriptResultEditor(viewState, actions)
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun TranscriptResultEditor(viewState: StandaloneRecordingViewState, actions: StandaloneRecordingActions)
{
    if (viewState.clientState.failureMessage != null)
    {
        Text(
            text = viewState.clientState.failureMessage.orEmpty(),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium
        )
    }
    if (viewState.clientState.usedDeterministicFallback)
    {
        Text(
            "Polished mode returned deterministic Clean text because local polishing was unavailable, timed out, exceeded its context, or failed an integrity check.",
            color = MaterialTheme.colorScheme.tertiary,
            style = MaterialTheme.typography.labelLarge
        )
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text("Show raw transcript")
        Switch(
            checked = viewState.showRawTranscript,
            onCheckedChange = actions.setRawTranscriptVisible,
            modifier = Modifier.semantics {
                contentDescription = "Show or hide the preserved raw speech recognition transcript"
            }
        )
    }
    if (viewState.showRawTranscript)
    {
        TranscriptCard(
            title = "Raw transcript",
            text = viewState.clientState.finalRawTranscript.ifEmpty {
                viewState.clientState.partialRawTranscript
            }
        )
    }
    OutlinedTextField(
        value = viewState.editableTranscript,
        onValueChange = actions.editTranscript,
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp),
        label = {
            Text("Final transcript editor")
        },
        supportingText = {
            Text("Manual edits affect only this visible result; the preserved raw transcript remains unchanged.")
        }
    )
    TranscriptActions(
        transcript = viewState.editableTranscript,
        onClear = actions.clearTranscript
    )
}

/**
 * Centralizes the operating-system permission checks used by the recorder and its launcher.
 */
private class RecordingPermissionState(private val context: Context)
{
    var microphonePermissionGranted by mutableStateOf(false)
        private set
    var notificationPermissionGranted by mutableStateOf(false)
        private set

    init
    {
        refresh()
    }

    fun refresh()
    {
        microphonePermissionGranted =
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        notificationPermissionGranted =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    fun requiredPermissions(): Array<String>
    {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        {
            arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        }
        else
        {
            arrayOf(Manifest.permission.RECORD_AUDIO)
        }
    }
}

@Composable
private fun ReadinessCard(
    connectionState: InferenceConnectionState,
    speechModelState: SpeechModelState,
    microphonePermissionGranted: Boolean,
    notificationPermissionGranted: Boolean,
    currentDownload: WorkInfo?,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onRequestPermission: () -> Unit,
    onOpenPermissionSettings: () -> Unit,
    onEnableKeyboard: () -> Unit,
    onSelectKeyboard: () -> Unit
)
{
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Readiness", style = MaterialTheme.typography.titleMedium)
            Text("Inference service: ${connectionState.readableName()}")
            Text("Speech model: ${speechModelState.readableName()}")
            Text("Microphone permission: ${if (microphonePermissionGranted) "Granted" else "Required"}")
            Text("Recording notification permission: ${if (notificationPermissionGranted) "Granted" else "Required"}")
            Text("Keyboard setup: enable ClearDictate, then select it as the current keyboard.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onEnableKeyboard) {
                    Text("1. Enable keyboard")
                }
                OutlinedButton(onClick = onSelectKeyboard) {
                    Text("2. Select keyboard")
                }
            }

            DownloadProgress(currentDownload)

            if (currentDownload?.state != WorkInfo.State.RUNNING &&
                currentDownload?.state != WorkInfo.State.ENQUEUED)
            {
                Button(onClick = onDownload) {
                    Text(
                        if (currentDownload?.state == WorkInfo.State.FAILED)
                        {
                            "Retry local models"
                        }
                        else
                        {
                            "Verify / download local models"
                        }
                    )
                }
            }

            if (currentDownload?.state == WorkInfo.State.RUNNING ||
                currentDownload?.state == WorkInfo.State.ENQUEUED)
            {
                OutlinedButton(onClick = onCancelDownload) {
                    Text("Cancel download")
                }
            }

            if (!microphonePermissionGranted || !notificationPermissionGranted)
            {
                Button(onClick = onRequestPermission) {
                    Text("Grant recording permissions")
                }
                OutlinedButton(onClick = onOpenPermissionSettings) {
                    Text("Open permission settings")
                }
            }
        }
    }
}

@Composable
private fun DownloadProgress(workInfo: WorkInfo?)
{
    if (workInfo?.state != WorkInfo.State.RUNNING && workInfo?.state != WorkInfo.State.ENQUEUED)
    {
        return
    }

    val installedBytes = workInfo.progress.getLong(
        RequiredModelsDownloadWorker.PROGRESS_BYTES_INSTALLED,
        0L
    )
    val totalBytes = workInfo.progress.getLong(
        RequiredModelsDownloadWorker.PROGRESS_TOTAL_BYTES,
        0L
    )
    val fraction = if (totalBytes > 0L)
    {
        installedBytes.toFloat() / totalBytes
    }
    else
    {
        0.0f
    }
    LinearProgressIndicator(
        progress = { fraction.coerceIn(0.0f, 1.0f) },
        modifier = Modifier.fillMaxWidth()
    )
    Text(
        if (totalBytes > 0L)
        {
            "Downloading ${formatMegabytes(installedBytes)} of ${formatMegabytes(totalBytes)}"
        }
        else
        {
            "Waiting for a network connection"
        }
    )
}

@Composable
private fun TranscriptModeSelector(
    selectedMode: TranscriptMode,
    enabled: Boolean,
    onSelected: (TranscriptMode) -> Unit
)
{
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Transcript mode", style = MaterialTheme.typography.titleMedium)
        Text("Mode selection applies to the next recording.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TranscriptMode.entries.forEach { mode ->
                FilterChip(
                    selected = selectedMode == mode,
                    onClick = {
                        onSelected(mode)
                    },
                    enabled = enabled,
                    label = {
                        Text(
                            if (mode == TranscriptMode.POLISHED)
                            {
                                "Polished (review required)"
                            }
                            else
                            {
                                mode.name.lowercase().replaceFirstChar { character -> character.uppercase() }
                            }
                        )
                    }
                )
            }
        }
    }
}

@Composable
private fun RecordingCard(
    clientState: com.cleardictate.inference.service.InferenceClientState,
    recordingDuration: String,
    recordEnabled: Boolean,
    onRecordOrStop: () -> Unit,
    onCancel: () -> Unit
)
{
    val active = clientState.recordingState == ClientRecordingState.PREPARING ||
        clientState.recordingState == ClientRecordingState.LISTENING ||
        clientState.recordingState == ClientRecordingState.SPEECH_DETECTED ||
        clientState.recordingState == ClientRecordingState.FINALIZING

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "Recording: ${clientState.recordingState.readableName()}",
                style = MaterialTheme.typography.titleMedium
            )
            Text(recordingDuration)
            LinearProgressIndicator(
                progress = { clientState.normalizedAudioLevel },
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics {
                        contentDescription = "Current microphone input level"
                    }
            )
            Text(
                clientState.partialRawTranscript.ifEmpty {
                    "Streaming partial recognition will appear here."
                }
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = onRecordOrStop,
                    enabled = recordEnabled && clientState.recordingState != ClientRecordingState.FINALIZING
                ) {
                    Text(if (active) "Stop and finalize" else "Record")
                }

                if (active)
                {
                    OutlinedButton(onClick = onCancel) {
                        Text("Cancel")
                    }
                }
            }
        }
    }
}

@Composable
private fun TranscriptCard(title: String, text: String)
{
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(text.ifEmpty { "No transcript yet." })
        }
    }
}

@Composable
private fun TranscriptActions(transcript: String, onClear: () -> Unit)
{
    val context = LocalContext.current

    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(
            onClick = {
                val clipboardManager = context.getSystemService(ClipboardManager::class.java)
                clipboardManager.setPrimaryClip(ClipData.newPlainText("ClearDictate transcript", transcript))
            },
            enabled = transcript.isNotEmpty()
        ) {
            Text("Copy")
        }
        OutlinedButton(
            onClick = {
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, transcript)
                }
                context.startActivity(Intent.createChooser(shareIntent, "Share transcript"))
            },
            enabled = transcript.isNotEmpty()
        ) {
            Text("Share")
        }
        OutlinedButton(onClick = onClear, enabled = transcript.isNotEmpty()) {
            Text("Clear")
        }
    }
}

@Composable
private fun rememberRecordingDuration(recordingActive: Boolean): String
{
    var elapsedMilliseconds by remember(recordingActive) {
        mutableLongStateOf(0L)
    }

    LaunchedEffect(recordingActive)
    {
        if (!recordingActive)
        {
            elapsedMilliseconds = 0L
            return@LaunchedEffect
        }

        val startMilliseconds = SystemClock.elapsedRealtime()

        while (true)
        {
            elapsedMilliseconds = SystemClock.elapsedRealtime() - startMilliseconds
            delay(250)
        }
    }

    val totalSeconds = elapsedMilliseconds / 1_000L
    return String.format(
        Locale.ROOT,
        "%02d:%02d",
        totalSeconds / 60L,
        totalSeconds % 60L
    )
}

private fun InferenceConnectionState.readableName(): String
{
    return name.lowercase().replace('_', ' ').replaceFirstChar { character -> character.uppercase() }
}

private fun SpeechModelState.readableName(): String
{
    return name.lowercase().replace('_', ' ').replaceFirstChar { character -> character.uppercase() }
}

private fun ClientRecordingState.readableName(): String
{
    return name.lowercase().replace('_', ' ').replaceFirstChar { character -> character.uppercase() }
}

private fun formatMegabytes(byteCount: Long): String
{
    return String.format(Locale.ROOT, "%.1f MB", byteCount / (1024.0 * 1024.0))
}
