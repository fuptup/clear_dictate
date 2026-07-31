package com.cleardictate.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cleardictate.domain.TranscriptFallbackReason
import com.cleardictate.domain.TranscriptMode
import com.cleardictate.desktop.inference.WindowsCaptureDevice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.awt.datatransfer.StringSelection

/**
 * Provides a Windows harness for exercising local microphone recognition and
 * the real shared transcript pipeline.
 *
 * This screen is intentionally not presented as the final dictation experience.
 * Saved history and system-wide insertion remain separate work.
 */
@Composable
@OptIn(ExperimentalComposeUiApi::class)
fun ClearDictateDesktopPreviewScreen(
    runtimeReadiness: DesktopRuntimeReadiness,
    speechRecorder: DesktopSpeechRecorder,
    transcriptProcessor: DesktopTranscriptProcessor
)
{
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            val coroutineScope = rememberCoroutineScope()
            val clipboard = LocalClipboard.current
            var previewState by remember { mutableStateOf(DesktopPreviewPresentationState()) }
            var showRawTranscript by remember { mutableStateOf(true) }
            var processingJob by remember { mutableStateOf<Job?>(null) }
            var processingInProgress by remember { mutableStateOf(false) }
            var restartInProgress by remember { mutableStateOf(false) }
            var recordingInProgress by remember { mutableStateOf(false) }
            var recordingCommandInProgress by remember { mutableStateOf(false) }
            var transcriptCollectionJob by remember { mutableStateOf<Job?>(null) }
            var captureDevices by remember { mutableStateOf<List<WindowsCaptureDevice>>(emptyList()) }
            var selectedEndpointIdentifier by remember { mutableStateOf("") }
            var captureDevicesLoading by remember { mutableStateOf(false) }
            var captureDeviceLoadFailed by remember { mutableStateOf(false) }

            val polishedModeAvailable = runtimeReadiness is DesktopRuntimeReadiness.Ready
            val recordingAvailable = runtimeReadiness is DesktopRuntimeReadiness.Ready

            LaunchedEffect(recordingAvailable, speechRecorder)
            {
                if (recordingAvailable)
                {
                    captureDevicesLoading = true
                    captureDeviceLoadFailed = false
                    try
                    {
                        captureDevices = speechRecorder.listActiveCaptureDevices()
                        if (selectedEndpointIdentifier.isNotEmpty() && captureDevices.none { it.endpointIdentifier == selectedEndpointIdentifier })
                        {
                            selectedEndpointIdentifier = ""
                        }
                    }
                    catch (cancellation: CancellationException)
                    {
                        throw cancellation
                    }
                    catch (_: Exception)
                    {
                        captureDevices = emptyList()
                        selectedEndpointIdentifier = ""
                        captureDeviceLoadFailed = true
                    }
                    finally
                    {
                        captureDevicesLoading = false
                    }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 32.dp, vertical = 24.dp)
            ) {
                PreviewHeader(runtimeReadiness)
                Spacer(modifier = Modifier.height(20.dp))
                RecordingControls(
                    recordingAvailable = recordingAvailable,
                    recordingInProgress = recordingInProgress,
                    commandInProgress = recordingCommandInProgress,
                    processingInProgress = processingInProgress,
                    captureDevices = captureDevices,
                    selectedEndpointIdentifier = selectedEndpointIdentifier,
                    captureDevicesLoading = captureDevicesLoading,
                    captureDeviceLoadFailed = captureDeviceLoadFailed,
                    onMicrophoneSelected = { selectedEndpointIdentifier = it },
                    onStart = {
                        recordingCommandInProgress = true
                        val selectedMicrophoneName = captureDevices
                            .firstOrNull { it.endpointIdentifier == selectedEndpointIdentifier }
                            ?.friendlyName
                            ?: "the Windows default microphone"
                        previewState = previewState.withStatus(
                            "Verifying and loading the local speech model, then opening $selectedMicrophoneName..."
                        )
                        coroutineScope.launch {
                            try
                            {
                                val recording = speechRecorder.startRecording(selectedEndpointIdentifier)
                                recordingInProgress = true
                                previewState = previewState.withStatus("Listening locally. Audio is not saved.")
                                transcriptCollectionJob?.cancel()
                                transcriptCollectionJob = launch {
                                    recording.transcript.collect { snapshot ->
                                        previewState = previewState
                                            .withRawTranscript(snapshot.visibleRawTranscript)
                                            .withStatus("Listening locally. Audio is not saved.")
                                    }
                                }
                            }
                            catch (_: Exception)
                            {
                                previewState = previewState.withStatus(
                                    "Microphone recording could not start. Check Windows microphone privacy and the local speech worker."
                                )
                            }
                            finally
                            {
                                recordingCommandInProgress = false
                            }
                        }
                    },
                    onStop = {
                        recordingCommandInProgress = true
                        previewState = previewState.withStatus("Finalizing local speech recognition...")
                        coroutineScope.launch {
                            try
                            {
                                val finalTranscript = speechRecorder.stopRecording()
                                transcriptCollectionJob?.cancel()
                                transcriptCollectionJob = null
                                previewState = previewState
                                    .withRawTranscript(finalTranscript)
                                    .withStatus("Recognition finalized locally. Choose a processing mode when ready.")
                            }
                            catch (_: Exception)
                            {
                                previewState = previewState.withStatus(
                                    "Speech finalization failed. The speech worker was discarded."
                                )
                            }
                            finally
                            {
                                recordingInProgress = false
                                recordingCommandInProgress = false
                            }
                        }
                    },
                    onCancel = {
                        recordingCommandInProgress = true
                        previewState = previewState.withStatus("Cancelling recording and waiting for native confirmation...")
                        coroutineScope.launch {
                            try
                            {
                                speechRecorder.cancelRecording()
                                transcriptCollectionJob?.cancel()
                                transcriptCollectionJob = null
                                previewState = previewState
                                    .withRawTranscript("")
                                    .withStatus("Recording cancelled. Captured audio and transcript buffers were discarded.")
                            }
                            catch (_: Exception)
                            {
                                previewState = previewState.withStatus(
                                    "Recording cancellation was not confirmed. The speech worker was discarded."
                                )
                            }
                            finally
                            {
                                recordingInProgress = false
                                recordingCommandInProgress = false
                            }
                        }
                    }
                )
                Spacer(modifier = Modifier.height(20.dp))
                TranscriptModeSelector(
                    selectedMode = previewState.selectedMode,
                    polishedModeAvailable = polishedModeAvailable,
                    enabled = !processingInProgress && !restartInProgress &&
                        !recordingInProgress && !recordingCommandInProgress,
                    onModeSelected = { previewState = previewState.withSelectedMode(it) }
                )
                Spacer(modifier = Modifier.height(20.dp))
                TranscriptEditors(
                    rawTranscript = previewState.rawTranscript,
                    outputTranscript = previewState.outputTranscript,
                    cleanTranscript = previewState.cleanTranscript,
                    showRawTranscript = showRawTranscript,
                    enabled = !processingInProgress && !recordingInProgress && !recordingCommandInProgress,
                    onRawTranscriptChanged = { previewState = previewState.withRawTranscript(it) },
                    onOutputTranscriptChanged = { updatedTranscript ->
                        previewState = previewState.withManuallyEditedOutput(updatedTranscript)
                    },
                    onShowRawTranscriptChanged = { showRawTranscript = it }
                )
                Spacer(modifier = Modifier.height(16.dp))
                ProcessingStatusCard(previewState, processingInProgress)
                Spacer(modifier = Modifier.height(16.dp))
                ProcessingControls(
                    processingInProgress = processingInProgress,
                    restartInProgress = restartInProgress,
                    recordingInProgress = recordingInProgress || recordingCommandInProgress,
                    selectedMode = previewState.selectedMode,
                    polishedModeAvailable = polishedModeAvailable,
                    outputTranscript = previewState.outputTranscript,
                    onProcess = {
                        val sourceForOperation = previewState.rawTranscript
                        val modeForOperation = previewState.selectedMode
                        previewState = previewState.withProcessingStatus(
                            if (modeForOperation == TranscriptMode.POLISHED)
                            {
                                "Starting or reusing the local model worker, then processing the transcript..."
                            }
                            else
                            {
                                "Running deterministic local processing..."
                            }
                        )
                        processingInProgress = true
                        val startedJob = coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) {
                            try
                            {
                                val processedTranscript = transcriptProcessor.process(sourceForOperation, modeForOperation)
                                previewState = previewState.withProcessedTranscript(processedTranscript)
                            }
                            catch (_: CancellationException)
                            {
                                previewState = previewState.withStatus("Processing cancelled; no result was inserted or saved.")
                            }
                            catch (_: Exception)
                            {
                                previewState = previewState.withStatus(
                                    "Local processing failed. Restart the local worker before retrying Polished mode."
                                )
                            }
                            finally
                            {
                                processingInProgress = false
                                processingJob = null
                            }
                        }
                        processingJob = startedJob
                    },
                    onCancel = {
                        previewState = previewState.withStatus(
                            "Cancelling local processing and waiting for worker confirmation..."
                        )
                        processingJob?.cancel()
                    },
                    onCopy = {
                        coroutineScope.launch {
                            clipboard.setClipEntry(ClipEntry(StringSelection(previewState.outputTranscript)))
                            previewState = previewState.withStatus("Selected output copied to the clipboard.")
                        }
                    },
                    onClear = {
                        previewState = previewState.cleared()
                    },
                    onRestartWorker = {
                        restartInProgress = true
                        coroutineScope.launch {
                            try
                            {
                                transcriptProcessor.restartWorker()
                                previewState = previewState.withStatus(
                                    "Local worker stopped. Polished mode will start a fresh worker."
                                )
                            }
                            finally
                            {
                                restartInProgress = false
                            }
                        }
                    }
                )
                Spacer(modifier = Modifier.height(20.dp))
                PrivacyBoundaryNotice()
            }
        }
    }
}

@Composable
private fun PreviewHeader(runtimeReadiness: DesktopRuntimeReadiness)
{
    Text(
        text = "ClearDictate",
        style = MaterialTheme.typography.headlineLarge,
        fontWeight = FontWeight.SemiBold
    )
    Text(
        text = "Developer preview — local microphone and text pipeline",
        modifier = Modifier.padding(top = 4.dp),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary
    )
    Text(
        text = when (runtimeReadiness)
        {
            is DesktopRuntimeReadiness.Ready ->
                "Required local files found. Each model is cryptographically verified before its isolated worker loads it."
            is DesktopRuntimeReadiness.Unavailable -> runtimeReadiness.explanation
        },
        modifier = Modifier.padding(top = 10.dp),
        style = MaterialTheme.typography.bodyMedium
    )
}

@Composable
private fun RecordingControls(
    recordingAvailable: Boolean,
    recordingInProgress: Boolean,
    commandInProgress: Boolean,
    processingInProgress: Boolean,
    captureDevices: List<WindowsCaptureDevice>,
    selectedEndpointIdentifier: String,
    captureDevicesLoading: Boolean,
    captureDeviceLoadFailed: Boolean,
    onMicrophoneSelected: (String) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onCancel: () -> Unit
)
{
    Text(text = "Local microphone recognition — Moonshine Medium Streaming", style = MaterialTheme.typography.titleMedium)
    MicrophoneDropdown(
        captureDevices = captureDevices,
        selectedEndpointIdentifier = selectedEndpointIdentifier,
        enabled = recordingAvailable && !recordingInProgress && !commandInProgress,
        onMicrophoneSelected = onMicrophoneSelected
    )
    Text(
        text = when
        {
            captureDevicesLoading -> "Finding active Windows microphone inputs…"
            captureDeviceLoadFailed -> "Named inputs could not be listed. System default remains available."
            captureDevices.isEmpty() && recordingAvailable -> "No active named inputs were reported. System default remains available."
            else -> "System default follows the Windows setting. A named input stays fixed for that recording."
        },
        modifier = Modifier.padding(top = 2.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Row(
        modifier = Modifier.padding(top = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Button(
            onClick = onStart,
            enabled = recordingAvailable && !recordingInProgress && !commandInProgress && !processingInProgress
        ) {
            Text(if (commandInProgress && !recordingInProgress) "Preparing…" else "Record")
        }
        Button(
            onClick = onStop,
            enabled = recordingInProgress && !commandInProgress
        ) {
            Text("Stop and transcribe")
        }
        OutlinedButton(
            onClick = onCancel,
            enabled = recordingInProgress && !commandInProgress
        ) {
            Text("Cancel recording")
        }
    }
    Text(
        text = if (recordingAvailable)
        {
            "Audio remains in bounded memory and is scrubbed after stop, cancellation, or failure."
        }
        else
        {
            "Build the local speech worker and install the pinned Moonshine model to enable recording."
        },
        modifier = Modifier.padding(top = 8.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun MicrophoneDropdown(captureDevices: List<WindowsCaptureDevice>, selectedEndpointIdentifier: String, enabled: Boolean, onMicrophoneSelected: (String) -> Unit)
{
    val microphoneOptions = buildDesktopMicrophoneOptions(captureDevices)
    val selectedOption = microphoneOptions.firstOrNull { option -> option.endpointIdentifier == selectedEndpointIdentifier }
        ?: microphoneOptions.first()
    var expanded by remember { mutableStateOf(false) }

    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = {
            PlainTooltip {
                Text("System default follows Windows. A named microphone stays fixed for that recording.")
            }
        },
        state = rememberTooltipState()
    ) {
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { requestedExpansion -> expanded = enabled && requestedExpansion },
            modifier = Modifier.padding(top = 10.dp)
        ) {
            OutlinedTextField(
                value = selectedOption.displayLabel,
                onValueChange = {},
                modifier = Modifier
                    .width(520.dp)
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled),
                enabled = enabled,
                readOnly = true,
                singleLine = true,
                label = { Text("Microphone input") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) }
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                microphoneOptions.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.displayLabel) },
                        onClick = {
                            onMicrophoneSelected(option.endpointIdentifier)
                            expanded = false
                        },
                        contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                    )
                }
            }
        }
    }
}

@Composable
private fun TranscriptModeSelector(selectedMode: TranscriptMode, polishedModeAvailable: Boolean, enabled: Boolean, onModeSelected: (TranscriptMode) -> Unit)
{
    Text(text = "Processing mode", style = MaterialTheme.typography.titleMedium)
    Row(
        modifier = Modifier.padding(top = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        TranscriptMode.entries.forEach { mode ->
            FilterChip(
                selected = selectedMode == mode,
                onClick = { onModeSelected(mode) },
                enabled = enabled && (mode != TranscriptMode.POLISHED || polishedModeAvailable),
                label = { Text(mode.displayName()) }
            )
        }
    }
    Text(
        text = selectedMode.description(),
        modifier = Modifier.padding(top = 8.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun TranscriptEditors(
    rawTranscript: String,
    outputTranscript: String,
    cleanTranscript: String,
    showRawTranscript: Boolean,
    enabled: Boolean,
    onRawTranscriptChanged: (String) -> Unit,
    onOutputTranscriptChanged: (String) -> Unit,
    onShowRawTranscriptChanged: (Boolean) -> Unit
)
{
    Row(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = "Recognizer transcript input", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = rawTranscript,
                onValueChange = onRawTranscriptChanged,
                modifier = Modifier.fillMaxWidth().height(210.dp).padding(top = 8.dp),
                enabled = enabled,
                placeholder = { Text("Paste or type the exact raw transcript here.") }
            )
        }
        Spacer(modifier = Modifier.width(20.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = "Selected output — editable", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = outputTranscript,
                onValueChange = onOutputTranscriptChanged,
                modifier = Modifier.fillMaxWidth().height(210.dp).padding(top = 8.dp),
                enabled = enabled,
                placeholder = { Text("Processed output appears here.") }
            )
        }
    }
    Row(
        modifier = Modifier.padding(top = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = showRawTranscript,
            onCheckedChange = onShowRawTranscriptChanged
        )
        Text("Show source and deterministic Clean result")
    }
    if (showRawTranscript)
    {
        Card(
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text("Exact source remains in the left editor.", style = MaterialTheme.typography.bodySmall)
                Text(
                    text = if (cleanTranscript.isEmpty()) "Clean result has not been generated yet." else "Clean result: $cleanTranscript",
                    modifier = Modifier.padding(top = 4.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@Composable
private fun ProcessingStatusCard(previewState: DesktopPreviewPresentationState, processingInProgress: Boolean)
{
    val fallbackUsed = previewState.fallbackReason != TranscriptFallbackReason.NONE
    val containerColor = when
    {
        fallbackUsed -> MaterialTheme.colorScheme.errorContainer
        processingInProgress -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = previewState.statusMessage,
                style = MaterialTheme.typography.bodyMedium
            )
            if (fallbackUsed)
            {
                Text(
                    text = "Polished output was rejected or unavailable; the deterministic Clean result was used (${previewState.fallbackReason.displayName()}).",
                    modifier = Modifier.padding(top = 4.dp),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun ProcessingControls(
    processingInProgress: Boolean,
    restartInProgress: Boolean,
    recordingInProgress: Boolean,
    selectedMode: TranscriptMode,
    polishedModeAvailable: Boolean,
    outputTranscript: String,
    onProcess: () -> Unit,
    onCancel: () -> Unit,
    onCopy: () -> Unit,
    onClear: () -> Unit,
    onRestartWorker: () -> Unit
)
{
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(
            onClick = onProcess,
            enabled = !processingInProgress && !restartInProgress && !recordingInProgress &&
                (selectedMode != TranscriptMode.POLISHED || polishedModeAvailable)
        ) {
            Text("Process locally")
        }
        if (processingInProgress)
        {
            OutlinedButton(onClick = onCancel) {
                Text("Cancel processing")
            }
        }
        OutlinedButton(
            onClick = onCopy,
            enabled = outputTranscript.isNotEmpty() && !processingInProgress && !recordingInProgress
        ) {
            Text("Copy output")
        }
        OutlinedButton(
            onClick = onClear,
            enabled = !processingInProgress && !restartInProgress && !recordingInProgress
        ) {
            Text("Clear")
        }
        OutlinedButton(
            onClick = onRestartWorker,
            enabled = !processingInProgress && !restartInProgress && !recordingInProgress && polishedModeAvailable
        ) {
            Text(if (restartInProgress) "Restarting…" else "Restart local worker")
        }
    }
    Text(
        text = "Restart stops the current model process. Use it after a worker failure or when testing a fresh model load.",
        modifier = Modifier.padding(top = 8.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun PrivacyBoundaryNotice()
{
    HorizontalDivider()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 14.dp)
            .background(Color.Transparent)
    ) {
        Text(
            text = "Offline developer harness: microphone audio is processed in bounded memory and is not saved; " +
                "no transcript history is stored. Speech and Polished text cross only private pipes to local child processes. " +
                "Process isolation is not a hostile-code sandbox.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun TranscriptMode.description(): String
{
    return when (this)
    {
        TranscriptMode.RAW -> "Normalizes whitespace only; the exact source remains visible."
        TranscriptMode.CLEAN -> "Uses deterministic rules for obvious fillers, duplication, spacing, punctuation, and capitalization."
        TranscriptMode.POLISHED -> "Runs Clean first, then local Qwen editing and semantic-integrity validation."
    }
}
