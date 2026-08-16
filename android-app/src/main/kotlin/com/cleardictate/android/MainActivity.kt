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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cleardictate.android.accessibility.ClearDictateAccessibilityService
import com.cleardictate.inference.OperationPrivacy
import com.cleardictate.inference.remote.PhonePairingPayload
import com.cleardictate.inference.service.ClientRecordingState
import com.cleardictate.inference.service.InferenceClientState
import com.cleardictate.inference.service.InferenceConnectionState
import com.cleardictate.inference.service.InferenceServiceClient
import com.cleardictate.inference.service.PcDictationClient
import com.cleardictate.inference.service.PcDictationEndpoint
import com.cleardictate.inference.service.PcHealthStatus
import com.cleardictate.inference.service.PcConnectionState
import com.cleardictate.inference.service.PcEndpointPreferences
import com.cleardictate.inference.service.SpeechModelState
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Hosts PC pairing, permissions, keyboard setup, and a standalone end-to-end recorder.
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
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ClearDictateScreen(inferenceServiceClient)
                }
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

/**
 * Owns the small amount of presentation state needed around the shared inference-service client.
 */
@Composable
private fun ClearDictateScreen(inferenceServiceClient: InferenceServiceClient)
{
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()
    val clientState by inferenceServiceClient.state.collectAsStateWithLifecycle()
    val permissions = remember(context) { RecordingPermissionState(context) }
    val endpointPreferences = remember(context) { PcEndpointPreferences(context) }
    val transport = remember { PcDictationClient() }
    val pairingScanner = remember(context) {
        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .enableAutoZoom()
            .build()
        GmsBarcodeScanning.getClient(context, options)
    }
    val savedEndpoint = remember { endpointPreferences.load() }
    var baseUrl by rememberSaveable { mutableStateOf(savedEndpoint?.baseUrl.orEmpty()) }
    var authorizationToken by remember { mutableStateOf(savedEndpoint?.authorizationToken.orEmpty()) }
    var pairedEndpointExists by remember { mutableStateOf(savedEndpoint != null) }
    var connectionMessage by remember {
        mutableStateOf(if (savedEndpoint == null) "Enter the address and token shown by ClearDictate on your PC." else "Checking paired PC…")
    }
    var connectionCheckRunning by remember { mutableStateOf(false) }
    var manualConnectionAttempted by remember { mutableStateOf(false) }
    var editableTranscript by remember { mutableStateOf("") }
    var floatingControlEnabled by remember { mutableStateOf(ClearDictateAccessibilityService.isEnabled(context)) }
    val recordingActive = clientState.recordingState.isActive()
    val recordingDuration = rememberRecordingDuration(clientState.recordingState.isCapturing())
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permissions.refresh()
    }

    DisposableEffect(lifecycleOwner, permissions)
    {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME)
            {
                permissions.refresh()
                floatingControlEnabled = ClearDictateAccessibilityService.isEnabled(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(clientState.completedOperationIdentifier)
    {
        if (clientState.completedOperationIdentifier != null)
        {
            editableTranscript = clientState.selectedTranscript
        }
    }

    LaunchedEffect(clientState.pcConnectionState, clientState.speechModelState, pairedEndpointExists, connectionCheckRunning)
    {
        if (pairedEndpointExists && !connectionCheckRunning && !manualConnectionAttempted)
        {
            connectionMessage = when (clientState.pcConnectionState)
            {
                PcConnectionState.CHECKING -> "Checking paired PC…"
                PcConnectionState.PREPARING_AI -> "PC connected; preparing AI…"
                PcConnectionState.DISCONNECTED -> "The paired PC is not reachable."
                PcConnectionState.CONNECTED -> when (clientState.speechModelState)
                {
                    SpeechModelState.READY -> "PC connected."
                    SpeechModelState.VERIFYING_AND_LOADING -> "Preparing PC dictation…"
                    SpeechModelState.FAILED -> "The PC is connected, but dictation is unavailable."
                    SpeechModelState.NOT_PREPARED -> connectionMessage
                }
            }
        }
    }

    val connectEndpoint: (PcDictationEndpoint) -> Unit = { endpoint ->
        focusManager.clearFocus()
        manualConnectionAttempted = true
        connectionCheckRunning = true
        connectionMessage = "Connecting…"
        coroutineScope.launch {
            val healthStatus = runCatching { transport.checkHealth(endpoint) }.getOrDefault(PcHealthStatus.UNAVAILABLE)
            if (healthStatus != PcHealthStatus.UNAVAILABLE)
            {
                endpointPreferences.save(endpoint.baseUrl, endpoint.authorizationToken)
                pairedEndpointExists = true
                connectionMessage = if (inferenceServiceClient.configurePcEndpoint(endpoint))
                {
                    if (healthStatus == PcHealthStatus.READY) "PC connected." else "PC connected; preparing AI…"
                }
                else
                {
                    "PC verified. Reopen ClearDictate to connect the recording service."
                }
            }
            else
            {
                connectionMessage = "Could not reach ClearDictate on that PC."
            }
            connectionCheckRunning = false
        }
    }
    val connect: () -> Unit = {
        val endpoint = runCatching { PcDictationEndpoint(baseUrl.trim(), authorizationToken.trim()) }.getOrNull()
        if (endpoint == null)
        {
            connectionMessage = "Enter a valid PC address and token."
        }
        else
        {
            connectEndpoint(endpoint)
        }
    }
    val scanPairing: () -> Unit = {
        connectionMessage = "Opening QR scanner…"
        pairingScanner.startScan()
            .addOnSuccessListener { barcode ->
                val payload = runCatching { PhonePairingPayload.decode(barcode.rawValue.orEmpty()) }.getOrNull()
                if (payload == null)
                {
                    connectionMessage = "That is not a valid ClearDictate pairing code."
                }
                else
                {
                    val endpoint = runCatching { PcDictationEndpoint(payload.endpointUrl, payload.authorizationToken) }.getOrNull()
                    if (endpoint == null)
                    {
                        connectionMessage = "The pairing code contains an invalid PC address."
                    }
                    else
                    {
                        baseUrl = payload.endpointUrl
                        authorizationToken = payload.authorizationToken
                        connectEndpoint(endpoint)
                    }
                }
            }
            .addOnCanceledListener { connectionMessage = "Pairing scan cancelled." }
            .addOnFailureListener { connectionMessage = "Could not open the QR scanner." }
    }

    val actions = ScreenActions(
        connect = connect,
        scanPairing = scanPairing,
        requestPermissions = { permissionLauncher.launch(permissions.requiredPermissions()) },
        openPermissionSettings = {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri()))
        },
        enableFloatingControl = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
        enableKeyboard = { context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) },
        selectKeyboard = { context.getSystemService(InputMethodManager::class.java).showInputMethodPicker() },
        recordOrStop = {
            if (recordingActive)
            {
                inferenceServiceClient.stopDictation()
            }
            else
            {
                editableTranscript = ""
                inferenceServiceClient.startDictation(OperationPrivacy.STANDARD)
            }
        },
        cancelRecording = inferenceServiceClient::cancelDictation,
        editTranscript = { editableTranscript = it },
        clearTranscript = {
            editableTranscript = ""
            inferenceServiceClient.clearCompletedTranscript()
        }
    )

    ScreenContent(
        state = ScreenState(
            clientState,
            permissions.microphonePermissionGranted,
            permissions.notificationPermissionGranted,
            floatingControlEnabled,
            connectionCheckRunning,
            connectionMessage,
            recordingActive,
            recordingDuration
        ),
        baseUrl = baseUrl,
        authorizationToken = authorizationToken,
        editableTranscript = editableTranscript,
        onBaseUrlChanged = { baseUrl = it },
        onAuthorizationTokenChanged = { authorizationToken = it },
        actions = actions
    )
}

/**
 * Groups UI-only values and derives whether recording can start without duplicating readiness rules.
 */
private data class ScreenState(
    val clientState: InferenceClientState,
    val microphonePermissionGranted: Boolean,
    val notificationPermissionGranted: Boolean,
    val floatingControlEnabled: Boolean,
    val connectionCheckRunning: Boolean,
    val connectionMessage: String,
    val recordingActive: Boolean,
    val recordingDuration: String
)
{
    val recordingReady: Boolean
        get() = microphonePermissionGranted && notificationPermissionGranted && clientState.connectionState == InferenceConnectionState.CONNECTED &&
            clientState.pcConnectionState == PcConnectionState.CONNECTED && clientState.speechModelState == SpeechModelState.READY
}

/**
 * Names mutations available to the stateless content tree.
 */
private data class ScreenActions(
    val connect: () -> Unit,
    val scanPairing: () -> Unit,
    val requestPermissions: () -> Unit,
    val openPermissionSettings: () -> Unit,
    val enableFloatingControl: () -> Unit,
    val enableKeyboard: () -> Unit,
    val selectKeyboard: () -> Unit,
    val recordOrStop: () -> Unit,
    val cancelRecording: () -> Unit,
    val editTranscript: (String) -> Unit,
    val clearTranscript: () -> Unit
)

/**
 * Renders pairing first because every recording path depends on a reachable PC service.
 */
@Composable
private fun ScreenContent(
    state: ScreenState,
    baseUrl: String,
    authorizationToken: String,
    editableTranscript: String,
    onBaseUrlChanged: (String) -> Unit,
    onAuthorizationTokenChanged: (String) -> Unit,
    actions: ScreenActions
)
{
    Scaffold(modifier = Modifier.safeDrawingPadding()) { contentPadding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(contentPadding).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("ClearDictate", style = MaterialTheme.typography.headlineLarge)
            Text("Speak on this phone. Audio streams to your PC while held, then the final transcript is polished on release.")
            PairingCard(baseUrl, authorizationToken, state, onBaseUrlChanged, onAuthorizationTokenChanged, actions.connect, actions.scanPairing)
            SetupCard(state, actions)
            RecordingCard(state, actions)
            if (state.clientState.failureMessage != null)
            {
                Text(state.clientState.failureMessage.orEmpty(), color = MaterialTheme.colorScheme.error)
            }
            TranscriptEditor(editableTranscript, actions.editTranscript, actions.clearTranscript)
        }
    }
}

/**
 * Collects the two values displayed by the PC and verifies them before persisting the pairing.
 */
@Composable
private fun PairingCard(
    baseUrl: String,
    authorizationToken: String,
    state: ScreenState,
    onBaseUrlChanged: (String) -> Unit,
    onAuthorizationTokenChanged: (String) -> Unit,
    onConnect: () -> Unit,
    onScanPairing: () -> Unit
)
{
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("PC connection", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = baseUrl,
                onValueChange = onBaseUrlChanged,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("PC address") },
                placeholder = { Text("http://192.168.1.20:8765") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
            )
            OutlinedTextField(
                value = authorizationToken,
                onValueChange = onAuthorizationTokenChanged,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Pairing token") },
                visualTransformation = PasswordVisualTransformation()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onScanPairing, enabled = !state.connectionCheckRunning) {
                    Text("Scan QR")
                }
                OutlinedButton(onClick = onConnect, enabled = !state.connectionCheckRunning && baseUrl.isNotBlank() && authorizationToken.isNotBlank()) {
                    Text(if (state.connectionCheckRunning) "Connecting…" else "Connect manually")
                }
            }
            Text(state.connectionMessage, style = MaterialTheme.typography.bodySmall)
            Text("Development build: use only on a trusted private Wi-Fi network.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

/**
 * Keeps operating-system permission and keyboard setup actions visible without dominating the recorder.
 */
@Composable
private fun SetupCard(state: ScreenState, actions: ScreenActions)
{
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Phone setup", style = MaterialTheme.typography.titleMedium)
            Text("PC service: ${state.clientState.pcConnectionState.readableName()}")
            Text("Microphone: ${if (state.microphonePermissionGranted) "Allowed" else "Permission required"}")
            if (!state.microphonePermissionGranted || !state.notificationPermissionGranted)
            {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = actions.requestPermissions) { Text("Allow recording") }
                    OutlinedButton(onClick = actions.openPermissionSettings) { Text("Settings") }
                }
            }
            Text("Floating microphone: ${if (state.floatingControlEnabled) "Enabled" else "Disabled"}")
            Text("Hold the floating microphone to dictate, or drag it immediately to reposition it.", style = MaterialTheme.typography.bodySmall)
            Button(onClick = actions.enableFloatingControl) {
                Text(if (state.floatingControlEnabled) "Accessibility settings" else "Enable floating microphone")
            }
            Text("Optional ClearDictate keyboard", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = actions.enableKeyboard) { Text("Enable keyboard") }
                OutlinedButton(onClick = actions.selectKeyboard) { Text("Select keyboard") }
            }
        }
    }
}

/**
 * Shows live microphone energy while capturing and an explicit processing state after release.
 */
@Composable
private fun RecordingCard(state: ScreenState, actions: ScreenActions)
{
    val finalizing = state.clientState.recordingState == ClientRecordingState.FINALIZING
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(state.clientState.recordingState.readableName(), style = MaterialTheme.typography.titleMedium)
            Text(state.recordingDuration)
            if (finalizing)
            {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Recording is being processed" }
                )
            }
            else
            {
                LinearProgressIndicator(
                    progress = { state.clientState.normalizedAudioLevel },
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Current microphone input level" }
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = actions.recordOrStop, enabled = state.recordingReady && !finalizing) {
                    Text(if (finalizing) "Processing…" else if (state.recordingActive) "Stop and process" else "Start recording")
                }
                if (state.recordingActive)
                {
                    OutlinedButton(onClick = actions.cancelRecording) { Text("Cancel") }
                }
            }
        }
    }
}

/**
 * Presents the PC-polished result for optional edits, copying, and explicit clearing.
 */
@Composable
private fun TranscriptEditor(transcript: String, onChanged: (String) -> Unit, onClear: () -> Unit)
{
    val context = LocalContext.current
    OutlinedTextField(
        value = transcript,
        onValueChange = onChanged,
        modifier = Modifier.fillMaxWidth().height(180.dp),
        label = { Text("Polished transcript") }
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = {
                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("ClearDictate transcript", transcript))
            },
            enabled = transcript.isNotEmpty()
        ) {
            Text("Copy")
        }
        OutlinedButton(onClick = onClear, enabled = transcript.isNotEmpty()) { Text("Clear") }
    }
}

/**
 * Centralizes permission checks shared by lifecycle refresh and the permission launcher.
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
        microphonePermissionGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        notificationPermissionGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
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

/**
 * Advances the visible timer only while the microphone session is active.
 */
@Composable
private fun rememberRecordingDuration(recordingActive: Boolean): String
{
    var elapsedMilliseconds by remember { mutableLongStateOf(0L) }
    LaunchedEffect(recordingActive)
    {
        if (!recordingActive)
        {
            return@LaunchedEffect
        }
        val startedAt = SystemClock.elapsedRealtime()
        elapsedMilliseconds = 0L
        while (true)
        {
            elapsedMilliseconds = SystemClock.elapsedRealtime() - startedAt
            delay(100L)
        }
    }
    val totalSeconds = elapsedMilliseconds / 1_000L
    return "%d:%02d".format(totalSeconds / 60L, totalSeconds % 60L)
}

private fun ClientRecordingState.isActive(): Boolean
{
    return this == ClientRecordingState.PREPARING || this == ClientRecordingState.LISTENING || this == ClientRecordingState.SPEECH_DETECTED || this == ClientRecordingState.FINALIZING
}

private fun ClientRecordingState.isCapturing(): Boolean
{
    return this == ClientRecordingState.PREPARING || this == ClientRecordingState.LISTENING || this == ClientRecordingState.SPEECH_DETECTED
}

private fun ClientRecordingState.readableName(): String
{
    return when (this)
    {
        ClientRecordingState.IDLE -> "Ready"
        ClientRecordingState.PREPARING -> "Starting microphone…"
        ClientRecordingState.LISTENING -> "Listening…"
        ClientRecordingState.SPEECH_DETECTED -> "Voice detected"
        ClientRecordingState.FINALIZING -> "Processing on PC…"
        ClientRecordingState.ERROR -> "Recording failed"
    }
}

private fun PcConnectionState.readableName(): String
{
    return when (this)
    {
        PcConnectionState.CHECKING -> "Checking…"
        PcConnectionState.PREPARING_AI -> "Preparing AI…"
        PcConnectionState.CONNECTED -> "Connected"
        PcConnectionState.DISCONNECTED -> "Not connected"
    }
}
