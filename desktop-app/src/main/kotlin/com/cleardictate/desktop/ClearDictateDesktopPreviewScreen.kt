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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.awt.datatransfer.StringSelection

/**
 * Provides a text-only Windows harness for exercising the real shared transcript pipeline.
 *
 * This screen is intentionally not presented as the final dictation experience. Microphone
 * capture, speech recognition, saved history, and system-wide insertion remain separate work.
 */
@Composable
@OptIn(ExperimentalComposeUiApi::class)
fun ClearDictateDesktopPreviewScreen(runtimeReadiness: DesktopRuntimeReadiness, transcriptProcessor: DesktopTranscriptProcessor)
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

            val polishedModeAvailable = runtimeReadiness is DesktopRuntimeReadiness.Ready

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 32.dp, vertical = 24.dp)
            ) {
                PreviewHeader(runtimeReadiness)
                Spacer(modifier = Modifier.height(20.dp))
                TranscriptModeSelector(
                    selectedMode = previewState.selectedMode,
                    polishedModeAvailable = polishedModeAvailable,
                    enabled = !processingInProgress && !restartInProgress,
                    onModeSelected = { previewState = previewState.withSelectedMode(it) }
                )
                Spacer(modifier = Modifier.height(20.dp))
                TranscriptEditors(
                    rawTranscript = previewState.rawTranscript,
                    outputTranscript = previewState.outputTranscript,
                    cleanTranscript = previewState.cleanTranscript,
                    showRawTranscript = showRawTranscript,
                    enabled = !processingInProgress,
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
        text = "Developer preview — text pipeline only",
        modifier = Modifier.padding(top = 4.dp),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary
    )
    Text(
        text = when (runtimeReadiness)
        {
            is DesktopRuntimeReadiness.Ready ->
                "Required local files found. The model hash will be verified before it is loaded on the first valid Polished request."
            is DesktopRuntimeReadiness.Unavailable -> runtimeReadiness.explanation
        },
        modifier = Modifier.padding(top = 10.dp),
        style = MaterialTheme.typography.bodyMedium
    )
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
            enabled = !processingInProgress && !restartInProgress && (selectedMode != TranscriptMode.POLISHED || polishedModeAvailable)
        ) {
            Text("Process locally")
        }
        if (processingInProgress)
        {
            OutlinedButton(onClick = onCancel) {
                Text("Cancel processing")
            }
        }
        OutlinedButton(onClick = onCopy, enabled = outputTranscript.isNotEmpty() && !processingInProgress) {
            Text("Copy output")
        }
        OutlinedButton(onClick = onClear, enabled = !processingInProgress && !restartInProgress) {
            Text("Clear")
        }
        OutlinedButton(onClick = onRestartWorker, enabled = !processingInProgress && !restartInProgress && polishedModeAvailable) {
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
            text = "Offline developer harness: this screen does not record audio or save history. Polished text crosses only a private pipe to a local child process. Process isolation is not a hostile-code sandbox.",
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
