package com.cleardictate.desktop

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cleardictate.desktop.inference.WindowsCaptureDevice
import kotlinx.coroutines.launch
import java.awt.datatransfer.StringSelection

/**
 * Presents the complete desktop push-to-talk flow: hold to capture, release to transcribe and polish.
 */
@Composable
@OptIn(ExperimentalComposeUiApi::class)
fun ClearDictateDesktopPreviewScreen(
    runtimeReadiness: DesktopRuntimeReadiness,
    speechRecorder: DesktopSpeechRecorder,
    dictationPipeline: DesktopDictationPipeline
)
{
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            val scope = rememberCoroutineScope()
            val clipboard = LocalClipboard.current
            val ready = runtimeReadiness is DesktopRuntimeReadiness.Ready
            var captureDevices by remember { mutableStateOf<List<WindowsCaptureDevice>>(emptyList()) }
            var selectedEndpointIdentifier by remember { mutableStateOf("") }
            var recording by remember { mutableStateOf(false) }
            var processing by remember { mutableStateOf(false) }
            var rawTranscript by remember { mutableStateOf("") }
            var polishedTranscript by remember { mutableStateOf("") }
            var status by remember { mutableStateOf(if (ready) "Hold the button and speak." else (runtimeReadiness as DesktopRuntimeReadiness.Unavailable).explanation) }

            LaunchedEffect(ready, speechRecorder)
            {
                if (ready)
                {
                    captureDevices = runCatching { speechRecorder.listActiveCaptureDevices() }.getOrDefault(emptyList())
                }
            }

            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 20.dp)
            ) {
                Text("ClearDictate", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
                Text("Local push-to-talk dictation", modifier = Modifier.padding(top = 4.dp), color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(14.dp))

                MicrophoneDropdown(captureDevices, selectedEndpointIdentifier, ready && !recording && !processing) {
                    selectedEndpointIdentifier = it
                }
                Spacer(Modifier.height(12.dp))

                PushToTalkButton(
                    enabled = ready && !processing,
                    recording = recording,
                    onPress = {
                        try
                        {
                            dictationPipeline.startRecording(selectedEndpointIdentifier)
                            recording = true
                            status = "Listening… release to transcribe."
                            true
                        }
                        catch (_: Exception)
                        {
                            status = "The microphone could not start. Check Windows microphone privacy settings."
                            false
                        }
                    },
                    onRelease = { released ->
                        recording = false
                        if (!released)
                        {
                            runCatching { dictationPipeline.cancelDictation() }
                            status = "Recording cancelled."
                        }
                        else
                        {
                            processing = true
                            status = "Transcribing with Qwen3-ASR, then polishing with Qwen3.5…"
                            try
                            {
                                val result = dictationPipeline.finishDictation()
                                rawTranscript = result.rawTranscript
                                polishedTranscript = result.polishedTranscript
                                status = "Polished text is ready."
                            }
                            catch (_: Exception)
                            {
                                status = "Local transcription or polishing failed. The recording was discarded."
                            }
                            finally
                            {
                                processing = false
                            }
                        }
                    }
                )

                Text(status, modifier = Modifier.padding(top = 10.dp), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    value = polishedTranscript,
                    onValueChange = { polishedTranscript = it },
                    modifier = Modifier.fillMaxWidth().height(140.dp),
                    enabled = !recording && !processing,
                    label = { Text("Polished text") },
                    placeholder = { Text("Release push-to-talk and the polished result will appear here.") }
                )
                OutlinedTextField(
                    value = rawTranscript,
                    onValueChange = {},
                    modifier = Modifier.fillMaxWidth().height(100.dp).padding(top = 10.dp),
                    readOnly = true,
                    label = { Text("Raw Qwen3-ASR transcript") }
                )
                Row(modifier = Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        enabled = polishedTranscript.isNotEmpty() && !recording && !processing,
                        onClick = {
                            scope.launch {
                                clipboard.setClipEntry(ClipEntry(StringSelection(polishedTranscript)))
                                status = "Polished text copied to the clipboard."
                            }
                        }
                    ) {
                        Text("Copy polished text")
                    }
                    OutlinedButton(
                        enabled = !recording && !processing,
                        onClick = {
                            rawTranscript = ""
                            polishedTranscript = ""
                            status = "Hold the button and speak."
                        }
                    ) {
                        Text("Clear")
                    }
                }
                Text(
                    "Audio stays in memory only until transcription finishes. Models and transcripts remain on this PC.",
                    modifier = Modifier.padding(top = 14.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Converts the press lifecycle into the exact start-on-press and finish-on-release contract.
 */
@Composable
private fun PushToTalkButton(enabled: Boolean, recording: Boolean, onPress: suspend () -> Boolean, onRelease: suspend (Boolean) -> Unit)
{
    Button(
        onClick = {},
        enabled = enabled,
        modifier = Modifier.width(260.dp).height(58.dp).pointerInput(enabled, onPress, onRelease) {
            detectTapGestures(
                onPress = {
                    if (enabled)
                    {
                        if (onPress())
                        {
                            onRelease(tryAwaitRelease())
                        }
                    }
                }
            )
        }
    ) {
        Text(if (recording) "Release to transcribe" else "Hold to talk")
    }
}

/**
 * Shows the Windows default and every active capture endpoint without spending vertical space.
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun MicrophoneDropdown(captureDevices: List<WindowsCaptureDevice>, selectedEndpointIdentifier: String, enabled: Boolean, onMicrophoneSelected: (String) -> Unit)
{
    val options = buildDesktopMicrophoneOptions(captureDevices)
    val selected = options.firstOrNull { it.endpointIdentifier == selectedEndpointIdentifier } ?: options.first()
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = enabled && it }) {
        OutlinedTextField(
            value = selected.displayLabel,
            onValueChange = {},
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled),
            enabled = enabled,
            readOnly = true,
            singleLine = true,
            label = { Text("Microphone input") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) }
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.displayLabel) },
                    onClick = {
                        onMicrophoneSelected(option.endpointIdentifier)
                        expanded = false
                    }
                )
            }
        }
    }
}
