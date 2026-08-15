package com.cleardictate.desktop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
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
import androidx.compose.material3.Switch
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import com.cleardictate.desktop.inference.WindowsCaptureDevice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.awt.datatransfer.StringSelection

/**
 * Presents the complete desktop push-to-talk flow: hold to capture, release to transcribe and polish.
 */
@Composable
@OptIn(ExperimentalComposeUiApi::class)
internal fun ClearDictateDesktopPreviewScreen(
    runtimeReadiness: DesktopRuntimeReadiness,
    speechRecorder: DesktopSpeechRecorder,
    dictationPipeline: DesktopDictationPipeline,
    phoneAccessConfiguration: DesktopPhoneAccessConfiguration,
    phoneServer: DesktopRemoteDictationServer,
    startupRegistration: DesktopStartupRegistration,
    onOpenHistory: () -> Unit,
    onOpenRules: () -> Unit
)
{
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            val scope = rememberCoroutineScope()
            val clipboard = LocalClipboard.current
            val ready = runtimeReadiness is DesktopRuntimeReadiness.Ready
            val microphoneActivity by speechRecorder.microphoneActivity.collectAsState()
            val latestPhoneTiming by phoneServer.lastSuccessfulTiming.collectAsState()
            val phoneServerState by phoneServer.state.collectAsState()
            var captureDevices by remember { mutableStateOf<List<WindowsCaptureDevice>>(emptyList()) }
            var selectedEndpointIdentifier by remember { mutableStateOf("") }
            var modelsReady by remember(ready) { mutableStateOf(false) }
            var preparingModels by remember(ready) { mutableStateOf(ready) }
            var recording by remember { mutableStateOf(false) }
            var processing by remember { mutableStateOf(false) }
            val rawTranscript = rememberTextFieldState()
            val polishedTranscript = rememberTextFieldState()
            var status by remember { mutableStateOf(if (ready) "Preparing AI…" else (runtimeReadiness as DesktopRuntimeReadiness.Unavailable).explanation) }
            var showPhoneSetup by remember { mutableStateOf(false) }
            var startWithWindows by remember(startupRegistration) { mutableStateOf(runCatching(startupRegistration::isEnabled).getOrDefault(false)) }

            LaunchedEffect(ready, speechRecorder, dictationPipeline, phoneServer)
            {
                if (ready)
                {
                    captureDevices = runCatching { speechRecorder.listActiveCaptureDevices() }.getOrDefault(emptyList())
                    preparingModels = true
                    phoneServer.setDictationReady(false)
                    try
                    {
                        dictationPipeline.prepareModels()
                        modelsReady = true
                        status = "Ready"
                        phoneServer.setDictationReady(true)
                        scope.launch {
                            runCatching { dictationPipeline.warmUpModels() }
                        }
                    }
                    catch (cancellation: CancellationException)
                    {
                        throw cancellation
                    }
                    catch (_: Exception)
                    {
                        modelsReady = false
                        phoneServer.setDictationReady(false)
                        status = "AI startup failed. Restart ClearDictate."
                    }
                    finally
                    {
                        preparingModels = false
                    }
                }
            }

            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("ClearDictate", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1.0F))
                    OutlinedButton(
                        onClick = onOpenHistory,
                        modifier = Modifier.width(74.dp).height(36.dp),
                        contentPadding = PaddingValues(horizontal = 6.dp)
                    ) {
                        Text("History")
                    }
                    Spacer(Modifier.width(6.dp))
                    OutlinedButton(
                        onClick = onOpenRules,
                        modifier = Modifier.width(60.dp).height(36.dp),
                        contentPadding = PaddingValues(horizontal = 6.dp)
                    ) {
                        Text("Rules")
                    }
                    Spacer(Modifier.width(6.dp))
                    OutlinedButton(
                        onClick = { showPhoneSetup = true },
                        modifier = Modifier.width(66.dp).height(36.dp),
                        contentPadding = PaddingValues(horizontal = 6.dp)
                    ) {
                        Text("Phone")
                    }
                    Spacer(Modifier.width(6.dp))
                    MicrophoneDropdown(
                        captureDevices,
                        selectedEndpointIdentifier,
                        ready && !recording && !processing,
                        Modifier.width(166.dp)
                    ) {
                        selectedEndpointIdentifier = it
                    }
                }
                Spacer(Modifier.height(6.dp))

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
                                    rawTranscript.setTextAndPlaceCursorAtEnd(result.rawTranscript)
                                    polishedTranscript.setTextAndPlaceCursorAtEnd(result.polishedTranscript)
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
                    Spacer(Modifier.width(8.dp))
                    DictationActivityIndicator(recording, preparingModels || processing, microphoneActivity, status, Modifier.weight(1.0F))
                }
                Spacer(Modifier.height(6.dp))
                ScrollableTranscriptField(
                    state = polishedTranscript,
                    modifier = Modifier.fillMaxWidth().height(108.dp),
                    enabled = !recording && !processing,
                    readOnly = false,
                    label = "Polished"
                )
                ScrollableTranscriptField(
                    state = rawTranscript,
                    modifier = Modifier.fillMaxWidth().height(66.dp).padding(top = 6.dp),
                    enabled = true,
                    readOnly = true,
                    label = "Raw"
                )
                Row(modifier = Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        enabled = polishedTranscript.text.isNotEmpty() && !recording && !processing,
                        modifier = Modifier.height(36.dp),
                        onClick = {
                            scope.launch {
                                clipboard.setClipEntry(ClipEntry(StringSelection(polishedTranscript.text.toString())))
                                status = "Copied"
                            }
                        }
                    ) {
                        Text("Copy")
                    }
                    OutlinedButton(
                        enabled = !recording && !processing,
                        modifier = Modifier.height(36.dp),
                        onClick = {
                            rawTranscript.setTextAndPlaceCursorAtEnd("")
                            polishedTranscript.setTextAndPlaceCursorAtEnd("")
                            status = "Ready"
                        }
                    ) {
                        Text("Clear")
                    }
                    Spacer(Modifier.weight(1.0F))
                    Row(
                        modifier = Modifier.height(36.dp).toggleable(
                            value = startWithWindows,
                            role = Role.Switch,
                            onValueChange = { enabled ->
                                try
                                {
                                    startupRegistration.setEnabled(enabled)
                                    startWithWindows = startupRegistration.isEnabled()
                                }
                                catch (_: Exception)
                                {
                                    startWithWindows = runCatching(startupRegistration::isEnabled).getOrDefault(false)
                                    status = "Could not update Windows startup."
                                }
                            }
                        ),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Start with Windows", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.width(6.dp))
                        Switch(checked = startWithWindows, onCheckedChange = null)
                    }
                }
            }

            if (showPhoneSetup)
            {
                PhoneSetupDialog(
                    configuration = phoneAccessConfiguration,
                    serverStatus = phoneServerState.readableName(),
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
 * Keeps each compact transcript box multiline and exposes its internal vertical position only when wrapped content overflows.
 */
@Composable
private fun ScrollableTranscriptField(state: TextFieldState, modifier: Modifier, enabled: Boolean, readOnly: Boolean, label: String)
{
    val scrollState = rememberScrollState()
    var emptyFieldScrollRange by remember { mutableIntStateOf(0) }
    val fieldIsEmpty = state.text.isEmpty()
    LaunchedEffect(fieldIsEmpty, scrollState.maxValue)
    {
        if (fieldIsEmpty)
        {
            emptyFieldScrollRange = scrollState.maxValue
        }
    }
    Box(modifier = modifier)
    {
        OutlinedTextField(
            state = state,
            modifier = Modifier.fillMaxSize(),
            enabled = enabled,
            readOnly = readOnly,
            lineLimits = TextFieldLineLimits.MultiLine(),
            scrollState = scrollState,
            label = { Text(label) }
        )
        TranscriptScrollbar(scrollState, !fieldIsEmpty && scrollState.maxValue > emptyFieldScrollRange)
    }
}

/**
 * Avoids reserving horizontal space for a scrollbar until the field has more wrapped lines than it can display.
 */
@Composable
private fun BoxScope.TranscriptScrollbar(scrollState: ScrollState, contentOverflows: Boolean)
{
    if (contentOverflows)
    {
        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(scrollState),
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(top = 12.dp, end = 3.dp, bottom = 4.dp)
        )
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
    DialogWindow(
        onCloseRequest = onDismiss,
        title = "ClearDictate Phone",
        state = rememberDialogState(width = 400.dp, height = 400.dp),
        resizable = false
    ) {
        MaterialTheme {
            Surface(modifier = Modifier.fillMaxSize()) {
                Column(modifier = Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Phone connection", modifier = Modifier.weight(1.0F), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        Text(serverStatus, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
                    }
                    lastTiming?.let { timing ->
                        Text("Last: ${formatLatency(timing)}", modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall)
                    }
                    if (pairingPayload == null)
                    {
                        Box(modifier = Modifier.fillMaxWidth().weight(1.0F), contentAlignment = Alignment.Center) {
                            Text("No private IPv4 address found")
                        }
                    }
                    else
                    {
                        val qrCode = remember(pairingPayload) { DesktopPhonePairingQrCode.render(pairingPayload.encode()) }
                        Image(qrCode, "QR code for pairing this phone with ClearDictate", modifier = Modifier.size(160.dp).align(Alignment.CenterHorizontally))
                        SelectionContainer {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(pairingPayload.endpointUrl, style = MaterialTheme.typography.bodySmall)
                                Text("Token: ${configuration.authorizationToken}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        if (configuration.endpointUrls.size > 1)
                        {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
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
                            "Scan in Android. Audio is authenticated but not encrypted; use Tailscale or another trusted private network.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    Spacer(Modifier.weight(1.0F))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = onDismiss) { Text("Close") }
                        TextButton(onClick = { pairingPayload?.let { payload -> onCopy(payload.encode()) } }, enabled = pairingPayload != null) {
                            Text("Copy details")
                        }
                    }
                }
            }
        }
    }
}

/**
 * Presents the supervised network lifecycle separately from the model-preparation lifecycle.
 */
private fun DesktopPhoneServerState.readableName(): String
{
    return when (this)
    {
        DesktopPhoneServerState.STARTING -> "Starting"
        DesktopPhoneServerState.PREPARING_AI -> "Preparing AI"
        DesktopPhoneServerState.READY -> "Ready"
        DesktopPhoneServerState.RECOVERING -> "Recovering"
        DesktopPhoneServerState.STOPPED -> "Stopped"
    }
}

/**
 * Keeps latency visible in the compact desktop UI without exposing any transcript content.
 */
internal fun formatLatency(timing: DesktopDictationTiming): String
{
    val overheadMilliseconds = timing.totalMilliseconds - timing.recognitionMilliseconds - timing.rewritingMilliseconds
    return "${timing.totalMilliseconds} ms = ${timing.recognitionMilliseconds} ASR + ${timing.rewritingMilliseconds} rewrite + $overheadMilliseconds overhead"
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
        modifier = Modifier.width(132.dp).height(36.dp).pointerInput(enabled) {
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
    Row(modifier = modifier.height(36.dp), verticalAlignment = Alignment.CenterVertically) {
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
            modifier = modifier.height(36.dp).menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled),
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surface,
            contentColor = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            border = BorderStroke(1.dp, if (enabled) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.outlineVariant)
        ) {
            Row(modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
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
