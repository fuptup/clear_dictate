package com.cleardictate.desktop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cleardictate.desktop.inference.WindowsCaptureDevice
import kotlinx.coroutines.CancellationException
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
    dictationPipeline: DesktopDictationPipeline,
    phoneAccessConfiguration: DesktopPhoneAccessConfiguration,
    phoneServer: DesktopRemoteDictationServer
)
{
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            val scope = rememberCoroutineScope()
            val clipboard = LocalClipboard.current
            val ready = runtimeReadiness is DesktopRuntimeReadiness.Ready
            val microphoneActivity by speechRecorder.microphoneActivity.collectAsState()
            val latestPhoneTiming by phoneServer.lastSuccessfulTiming.collectAsState()
            var captureDevices by remember { mutableStateOf<List<WindowsCaptureDevice>>(emptyList()) }
            var selectedEndpointIdentifier by remember { mutableStateOf("") }
            var modelsReady by remember(ready) { mutableStateOf(false) }
            var preparingModels by remember(ready) { mutableStateOf(ready) }
            var recording by remember { mutableStateOf(false) }
            var processing by remember { mutableStateOf(false) }
            var rawTranscript by remember { mutableStateOf("") }
            var polishedTranscript by remember { mutableStateOf("") }
            var status by remember { mutableStateOf(if (ready) "Preparing AI…" else (runtimeReadiness as DesktopRuntimeReadiness.Unavailable).explanation) }
            var phoneServerStatus by remember { mutableStateOf("Waiting for AI") }
            var showPhoneSetup by remember { mutableStateOf(false) }

            LaunchedEffect(ready, speechRecorder, dictationPipeline, phoneServer)
            {
                if (ready)
                {
                    captureDevices = runCatching { speechRecorder.listActiveCaptureDevices() }.getOrDefault(emptyList())
                    preparingModels = true
                    try
                    {
                        dictationPipeline.prepareModels()
                        modelsReady = true
                        status = "Ready"
                        phoneServerStatus = try
                        {
                            phoneServer.start()
                            scope.launch {
                                runCatching { dictationPipeline.warmUpModels() }
                            }
                            "Ready"
                        }
                        catch (_: Exception)
                        {
                            "Unavailable: could not open port ${phoneAccessConfiguration.port}"
                        }
                    }
                    catch (cancellation: CancellationException)
                    {
                        throw cancellation
                    }
                    catch (_: Exception)
                    {
                        modelsReady = false
                        status = "AI startup failed. Restart ClearDictate."
                    }
                    finally
                    {
                        preparingModels = false
                    }
                }
            }

            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 16.dp)
            ) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("ClearDictate", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1.0F))
                    OutlinedButton(
                        onClick = { showPhoneSetup = true },
                        modifier = Modifier.width(74.dp).height(40.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp)
                    ) {
                        Text("Phone")
                    }
                    Spacer(Modifier.width(8.dp))
                    MicrophoneDropdown(
                        captureDevices,
                        selectedEndpointIdentifier,
                        ready && !recording && !processing,
                        Modifier.width(240.dp)
                    ) {
                        selectedEndpointIdentifier = it
                    }
                }
                Spacer(Modifier.height(10.dp))

                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    PushToTalkButton(
                        enabled = modelsReady && !processing,
                        recording = recording,
                        onPress = {
                            try
                            {
                                dictationPipeline.startRecording(selectedEndpointIdentifier)
                                recording = true
                                true
                            }
                            catch (_: Exception)
                            {
                                status = "Microphone unavailable."
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
                                status = "Processing…"
                                try
                                {
                                    val result = dictationPipeline.finishDictation()
                                    rawTranscript = result.rawTranscript
                                    polishedTranscript = result.polishedTranscript
                                    status = "Ready \u2022 ${formatLatency(result.timing)}"
                                }
                                catch (_: Exception)
                                {
                                    status = "Processing failed. Try again."
                                }
                                finally
                                {
                                    processing = false
                                }
                            }
                        }
                    )
                    Spacer(Modifier.width(12.dp))
                    DictationActivityIndicator(recording, preparingModels || processing, microphoneActivity, status, Modifier.weight(1.0F))
                }
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = polishedTranscript,
                    onValueChange = { polishedTranscript = it },
                    modifier = Modifier.fillMaxWidth().height(124.dp),
                    enabled = !recording && !processing,
                    label = { Text("Polished") }
                )
                OutlinedTextField(
                    value = rawTranscript,
                    onValueChange = {},
                    modifier = Modifier.fillMaxWidth().height(76.dp).padding(top = 8.dp),
                    readOnly = true,
                    label = { Text("Raw") }
                )
                Row(modifier = Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        enabled = polishedTranscript.isNotEmpty() && !recording && !processing,
                        onClick = {
                            scope.launch {
                                clipboard.setClipEntry(ClipEntry(StringSelection(polishedTranscript)))
                                status = "Copied"
                            }
                        }
                    ) {
                        Text("Copy")
                    }
                    OutlinedButton(
                        enabled = !recording && !processing,
                        onClick = {
                            rawTranscript = ""
                            polishedTranscript = ""
                            status = "Ready"
                        }
                    ) {
                        Text("Clear")
                    }
                }
            }

            if (showPhoneSetup)
            {
                PhoneSetupDialog(
                    configuration = phoneAccessConfiguration,
                    serverStatus = phoneServerStatus,
                    lastTiming = latestPhoneTiming,
                    onCopy = { pairingText ->
                        scope.launch {
                            clipboard.setClipEntry(ClipEntry(StringSelection(pairingText)))
                            status = "Phone setup copied"
                        }
                    },
                    onDismiss = { showPhoneSetup = false }
                )
            }
        }
    }
}

/**
 * Exposes developer pairing details without adding permanent instructional text to the compact dictation surface.
 */
@Composable
private fun PhoneSetupDialog(
    configuration: DesktopPhoneAccessConfiguration,
    serverStatus: String,
    lastTiming: DesktopDictationTiming?,
    onCopy: (String) -> Unit,
    onDismiss: () -> Unit
)
{
    var selectedEndpointIndex by remember(configuration.endpointUrls) { mutableIntStateOf(0) }
    val selectedEndpoint = configuration.endpointUrls.getOrNull(selectedEndpointIndex)
    val pairingPayload = selectedEndpoint?.let(configuration::pairingPayload)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Phone connection") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Status: $serverStatus", modifier = Modifier.fillMaxWidth())
                lastTiming?.let { timing ->
                    Text("Last phone dictation: ${formatLatency(timing)}", modifier = Modifier.fillMaxWidth())
                }
                if (pairingPayload == null)
                {
                    Text("No private IPv4 address found", modifier = Modifier.fillMaxWidth())
                }
                else
                {
                    val qrCode = remember(pairingPayload) { DesktopPhonePairingQrCode.render(pairingPayload.encode()) }
                    Image(qrCode, "QR code for pairing this phone with ClearDictate", modifier = Modifier.size(220.dp))
                    SelectionContainer {
                        Column {
                            Text(pairingPayload.endpointUrl)
                            Text("Token: ${configuration.authorizationToken}")
                        }
                    }
                    if (configuration.endpointUrls.size > 1)
                    {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = {
                                selectedEndpointIndex = (selectedEndpointIndex - 1 + configuration.endpointUrls.size) % configuration.endpointUrls.size
                            }) {
                                Text("Previous address")
                            }
                            TextButton(onClick = {
                                selectedEndpointIndex = (selectedEndpointIndex + 1) % configuration.endpointUrls.size
                            }) {
                                Text("Next address")
                            }
                        }
                    }
                }
                SelectionContainer {
                    Text(
                        "Scan in the Android app. Audio is authenticated but not encrypted, so use a trusted private network.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { pairingPayload?.let { payload -> onCopy(payload.encode()) } }, enabled = pairingPayload != null) {
                Text("Copy details")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

/**
 * Keeps latency visible in the compact desktop UI without exposing any transcript content.
 */
private fun formatLatency(timing: DesktopDictationTiming): String
{
    return "${timing.totalMilliseconds} ms total (${timing.recognitionMilliseconds} ms ASR, ${timing.rewritingMilliseconds} ms rewrite)"
}

/**
 * Converts the press lifecycle into the exact start-on-press and finish-on-release contract.
 */
@Composable
private fun PushToTalkButton(enabled: Boolean, recording: Boolean, onPress: suspend () -> Boolean, onRelease: suspend (Boolean) -> Unit)
{
    val releaseScope = rememberCoroutineScope()
    val currentOnPress by rememberUpdatedState(onPress)
    val currentOnRelease by rememberUpdatedState(onRelease)

    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (enabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.width(148.dp).height(40.dp).pointerInput(enabled) {
            detectTapGestures(
                onPress = {
                    if (enabled)
                    {
                        if (currentOnPress())
                        {
                            val released = tryAwaitRelease()
                            releaseScope.launch { currentOnRelease(released) }
                        }
                    }
                }
            )
        }
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(if (recording) "Release" else "Hold to talk", fontWeight = FontWeight.Medium)
        }
    }
}

/**
 * Renders compact activity feedback alongside the push-to-talk control.
 */
@Composable
private fun DictationActivityIndicator(recording: Boolean, processing: Boolean, microphoneActivity: Float, status: String, modifier: Modifier)
{
    Row(modifier = modifier.height(40.dp), verticalAlignment = Alignment.CenterVertically) {
        when
        {
            recording ->
            {
                Text("Mic", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                LinearProgressIndicator(
                    progress = { microphoneActivity },
                    modifier = Modifier.weight(1.0F).padding(start = 8.dp).height(8.dp)
                )
            }
            processing ->
            {
                CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 3.dp)
                Text(status, modifier = Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyMedium)
            }
            else -> Text(status, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/**
 * Shows the Windows default and every active capture endpoint without spending vertical space.
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun MicrophoneDropdown(captureDevices: List<WindowsCaptureDevice>, selectedEndpointIdentifier: String, enabled: Boolean, modifier: Modifier, onMicrophoneSelected: (String) -> Unit)
{
    val options = buildDesktopMicrophoneOptions(captureDevices)
    val selected = options.firstOrNull { it.endpointIdentifier == selectedEndpointIdentifier } ?: options.first()
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = enabled && it }) {
        Surface(
            modifier = modifier.height(42.dp).menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled),
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surface,
            contentColor = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            border = BorderStroke(1.dp, if (enabled) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.outlineVariant)
        ) {
            Row(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(selected.displayLabel, modifier = Modifier.weight(1.0F), maxLines = 1, overflow = TextOverflow.Ellipsis)
                ExposedDropdownMenuDefaults.TrailingIcon(expanded)
            }
        }
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
