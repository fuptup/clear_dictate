package com.cleardictate.inference.service

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.cleardictate.domain.TranscriptMode
import com.cleardictate.inference.OperationPrivacy
import com.cleardictate.inference.service.ipc.IClearDictateInferenceCallback
import com.cleardictate.inference.service.ipc.IClearDictateInferenceService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID

enum class InferenceConnectionState
{
    DISCONNECTED,
    CONNECTING,
    CONNECTED
}

enum class ClientRecordingState
{
    IDLE,
    PREPARING,
    LISTENING,
    SPEECH_DETECTED,
    FINALIZING,
    ERROR
}

/**
 * Contains bounded presentation-safe state; no target application or editor metadata is retained.
 */
data class InferenceClientState(
    val connectionState: InferenceConnectionState = InferenceConnectionState.DISCONNECTED,
    val pcConnectionState: PcConnectionState = PcConnectionState.CHECKING,
    val speechModelState: SpeechModelState = SpeechModelState.NOT_PREPARED,
    val recordingState: ClientRecordingState = ClientRecordingState.IDLE,
    val normalizedAudioLevel: Float = 0.0f,
    val partialRawTranscript: String = "",
    val finalRawTranscript: String = "",
    val cleanTranscript: String = "",
    val polishedTranscript: String? = null,
    val selectedTranscript: String = "",
    val selectedMode: TranscriptMode = TranscriptMode.RAW,
    val usedDeterministicFallback: Boolean = false,
    val completedOperationIdentifier: String? = null,
    val failureMessage: String? = null
)
{
    override fun toString(): String
    {
        val redactedCompletedOperationIdentifier = if (completedOperationIdentifier == null)
        {
            null
        }
        else
        {
            "<redacted>"
        }
        return "InferenceClientState(connectionState=$connectionState, pcConnectionState=$pcConnectionState, speechModelState=$speechModelState, recordingState=$recordingState, " +
            "normalizedAudioLevel=$normalizedAudioLevel, selectedMode=$selectedMode, usedDeterministicFallback=$usedDeterministicFallback, " +
            "completedOperationIdentifier=$redactedCompletedOperationIdentifier, failureMessage=$failureMessage, transcripts=<redacted>)"
    }
}

/**
 * Hides generated Binder types behind operation-identity checks shared by the app and keyboard.
 */
class InferenceServiceClient(
    context: Context,
    private val clientSessionIdentifier: String = UUID.randomUUID().toString()
) : AutoCloseable
{
    private val applicationContext = context.applicationContext
    private val clientLock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val mutableState = MutableStateFlow(InferenceClientState())
    @Volatile
    private var remoteService: IClearDictateInferenceService? = null

    @Volatile
    private var activeOperationIdentifier: String? = null
    @Volatile
    private var closed = false
    private val cancelledOperationIdentifiers = mutableSetOf<String>()
    private var bound = false

    val state: StateFlow<InferenceClientState> = mutableState.asStateFlow()

    private val callback = object : IClearDictateInferenceCallback.Stub()
    {
        override fun onPcConnectionStateChanged(stateCode: Int)
        {
            postIfOpen {
                mutableState.update { currentState ->
                    currentState.copy(pcConnectionState = parsePcConnectionState(stateCode))
                }
            }
        }

        override fun onSpeechModelStateChanged(stateCode: Int)
        {
            postIfOpen {
                mutableState.update { currentState ->
                    currentState.copy(speechModelState = parseSpeechModelState(stateCode))
                }
            }
        }

        override fun onOperationAccepted(operationIdentifier: String)
        {
        }

        override fun onOperationBusy(operationIdentifier: String)
        {
            postIfOpen {
                if (isActive(operationIdentifier))
                {
                    failActiveOperation("Another ClearDictate client is already recording.")
                }
            }
        }

        override fun onRecordingStateChanged(operationIdentifier: String, stateCode: Int)
        {
            postIfOpen {
                if (isActive(operationIdentifier))
                {
                    mutableState.update { currentState ->
                        currentState.copy(
                            recordingState = parseRecordingState(stateCode),
                            failureMessage = null
                        )
                    }
                }
            }
        }

        override fun onAudioLevel(operationIdentifier: String, normalizedLevel: Float)
        {
            postIfOpen {
                if (isActive(operationIdentifier))
                {
                    mutableState.update { currentState ->
                        currentState.copy(normalizedAudioLevel = normalizedLevel.coerceIn(0.0f, 1.0f))
                    }
                }
            }
        }

        override fun onPartialTranscript(operationIdentifier: String, rawPartialTranscript: String)
        {
            postIfOpen {
                if (isActive(operationIdentifier))
                {
                    mutableState.update { currentState ->
                        currentState.copy(partialRawTranscript = rawPartialTranscript)
                    }
                }
            }
        }

        override fun onFinalTranscript(
            operationIdentifier: String,
            rawTranscript: String,
            cleanTranscript: String,
            polishedTranscript: String,
            selectedTranscript: String,
            selectedModeCode: Int,
            usedDeterministicFallback: Boolean,
            fallbackReasonCode: Int
        )
        {
            postIfOpen {
                if (isActive(operationIdentifier) && !isCancelled(operationIdentifier))
                {
                    activeOperationIdentifier = null
                    mutableState.update { currentState ->
                        currentState.copy(
                            recordingState = ClientRecordingState.IDLE,
                            normalizedAudioLevel = 0.0f,
                            partialRawTranscript = "",
                            finalRawTranscript = rawTranscript,
                            cleanTranscript = cleanTranscript,
                            polishedTranscript = polishedTranscript.ifEmpty { null },
                            selectedTranscript = selectedTranscript,
                            selectedMode = parseTranscriptMode(selectedModeCode),
                            usedDeterministicFallback = usedDeterministicFallback,
                            completedOperationIdentifier = operationIdentifier,
                            failureMessage = null
                        )
                    }
                }
            }
        }

        override fun onOperationCancelled(operationIdentifier: String)
        {
            postIfOpen {
                val cancellationWasRequested = synchronized(clientLock)
                {
                    cancelledOperationIdentifiers.remove(operationIdentifier)
                }

                if (isActive(operationIdentifier) || cancellationWasRequested)
                {
                    activeOperationIdentifier = null
                    mutableState.update { currentState ->
                        currentState.copy(
                            recordingState = ClientRecordingState.IDLE,
                            normalizedAudioLevel = 0.0f,
                            partialRawTranscript = "",
                            finalRawTranscript = "",
                            cleanTranscript = "",
                            polishedTranscript = null,
                            selectedTranscript = "",
                            usedDeterministicFallback = false,
                            completedOperationIdentifier = null,
                            failureMessage = null
                        )
                    }
                }
            }
        }

        override fun onFailure(operationIdentifier: String, failureCode: Int)
        {
            postIfOpen {
                if (operationIdentifier.isEmpty() || isActive(operationIdentifier))
                {
                    failActiveOperation(failureMessage(failureCode))
                }
            }
        }
    }

    private val serviceConnection = object : ServiceConnection
    {
        override fun onServiceConnected(componentName: ComponentName, binder: IBinder)
        {
            if (closed)
            {
                return
            }

            val service = IClearDictateInferenceService.Stub.asInterface(binder)
            remoteService = service
            mutableState.update { currentState ->
                currentState.afterServiceConnected()
            }

            try
            {
                service.registerClient(clientSessionIdentifier, callback)
                service.prepareSpeechModel()
            }
            catch (_: Exception)
            {
                handleServiceDisconnected()
            }
        }

        override fun onServiceDisconnected(componentName: ComponentName)
        {
            if (!closed)
            {
                handleServiceDisconnected()
            }
        }

        override fun onBindingDied(componentName: ComponentName)
        {
            handleBindingDied()
        }

        override fun onNullBinding(componentName: ComponentName)
        {
            handleBindingDied()
        }
    }

    fun bind()
    {
        if (closed || bound)
        {
            return
        }

        mutableState.update { currentState ->
            currentState.copy(connectionState = InferenceConnectionState.CONNECTING)
        }
        bound = applicationContext.bindService(
            Intent(applicationContext, ClearDictateInferenceService::class.java),
            serviceConnection,
            Context.BIND_AUTO_CREATE
        )

        if (!bound)
        {
            handleServiceDisconnected()
        }
    }

    /**
     * Promotes the service from the user's visible tap before sending the bounded begin command.
     */
    fun startDictation(privacy: OperationPrivacy): Boolean
    {
        if (closed)
        {
            return false
        }

        val service = remoteService

        if (service == null)
        {
            mutableState.update { currentState ->
                currentState.copy(failureMessage = "The recording service is not connected.")
            }
            return false
        }

        synchronized(clientLock)
        {
            if (activeOperationIdentifier != null)
            {
                mutableState.update { currentState ->
                    currentState.copy(failureMessage = "A ClearDictate recording is already active.")
                }
                return false
            }
        }

        if (mutableState.value.pcConnectionState != PcConnectionState.CONNECTED || mutableState.value.speechModelState != SpeechModelState.READY)
        {
            mutableState.update { currentState ->
                currentState.copy(failureMessage = "The paired PC is not ready.")
            }
            return false
        }

        if (ContextCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED)
        {
            mutableState.update { currentState ->
                currentState.copy(failureMessage = "Microphone permission is required.")
            }
            return false
        }

        val operationIdentifier = UUID.randomUUID().toString()
        synchronized(clientLock)
        {
            if (activeOperationIdentifier != null)
            {
                return false
            }
            activeOperationIdentifier = operationIdentifier
            cancelledOperationIdentifiers.remove(operationIdentifier)
        }
        mutableState.update { currentState ->
            currentState.copy(
                recordingState = ClientRecordingState.PREPARING,
                normalizedAudioLevel = 0.0f,
                partialRawTranscript = "",
                finalRawTranscript = "",
                cleanTranscript = "",
                polishedTranscript = null,
                selectedTranscript = "",
                usedDeterministicFallback = false,
                completedOperationIdentifier = null,
                failureMessage = null
            )
        }

        return try
        {
            ContextCompat.startForegroundService(
                applicationContext,
                ClearDictateInferenceService.createMicrophoneForegroundIntent(
                    applicationContext,
                    clientSessionIdentifier,
                    operationIdentifier,
                    privacy
                )
            )
            true
        }
        catch (_: Exception)
        {
            failActiveOperation(
                "Android did not allow microphone foreground execution from the current screen."
            )
            false
        }
    }

    fun stopDictation()
    {
        val operationIdentifier = activeOperationIdentifier ?: return

        try
        {
            remoteService?.stopDictation(clientSessionIdentifier, operationIdentifier)
        }
        catch (_: Exception)
        {
            handleServiceDisconnected()
        }
    }

    fun cancelDictation()
    {
        val operationIdentifier = synchronized(clientLock)
        {
            val currentOperationIdentifier = activeOperationIdentifier ?: return
            cancelledOperationIdentifiers += currentOperationIdentifier
            activeOperationIdentifier = null
            currentOperationIdentifier
        }

        clearOperationTranscripts(ClientRecordingState.IDLE, failureMessage = null)

        try
        {
            remoteService?.cancelDictation(clientSessionIdentifier, operationIdentifier)
        }
        catch (_: Exception)
        {
            handleServiceDisconnected()
        }
    }

    fun retrySpeechModelPreparation()
    {
        try
        {
            remoteService?.prepareSpeechModel()
        }
        catch (_: Exception)
        {
            handleServiceDisconnected()
        }
    }

    /**
     * Copies a verified pairing into the isolated inference process before asking it to re-check the PC.
     */
    fun configurePcEndpoint(endpoint: PcDictationEndpoint): Boolean
    {
        val service = remoteService ?: return false
        return try
        {
            service.configurePcEndpoint(clientSessionIdentifier, endpoint.baseUrl, endpoint.authorizationToken)
        }
        catch (_: Exception)
        {
            false
        }
    }

    /**
     * Clears completed text when an Input Method Editor session ends or private text is inserted.
     */
    fun clearCompletedTranscript()
    {
        mutableState.update { currentState ->
            currentState.copy(
                partialRawTranscript = "",
                finalRawTranscript = "",
                cleanTranscript = "",
                polishedTranscript = null,
                selectedTranscript = "",
                usedDeterministicFallback = false,
                completedOperationIdentifier = null
            )
        }
    }

    override fun close()
    {
        val operationIdentifierToCancel = synchronized(clientLock)
        {
            if (closed)
            {
                return
            }

            closed = true
            val currentOperationIdentifier = activeOperationIdentifier
            currentOperationIdentifier?.let { cancelledOperationIdentifiers += it }
            activeOperationIdentifier = null
            currentOperationIdentifier
        }
        mainHandler.removeCallbacksAndMessages(null)

        try
        {
            operationIdentifierToCancel?.let { operationIdentifier ->
                remoteService?.cancelDictation(clientSessionIdentifier, operationIdentifier)
            }
        }
        catch (_: Exception)
        {
            // The remote process may already be gone.
        }

        try
        {
            remoteService?.unregisterClient(clientSessionIdentifier)
        }
        catch (_: Exception)
        {
            // The remote process may already be gone.
        }

        remoteService = null

        if (bound)
        {
            applicationContext.unbindService(serviceConnection)
            bound = false
        }

        synchronized(clientLock)
        {
            cancelledOperationIdentifiers.clear()
        }
        mutableState.value = InferenceClientState()
    }

    private fun handleServiceDisconnected()
    {
        remoteService = null
        synchronized(clientLock)
        {
            activeOperationIdentifier = null
            cancelledOperationIdentifiers.clear()
        }
        clearOperationTranscripts(
            recordingState = ClientRecordingState.ERROR,
            failureMessage = "The recording service stopped. Reconnect to try again.",
            connectionState = InferenceConnectionState.DISCONNECTED,
            pcConnectionState = PcConnectionState.DISCONNECTED
        )
    }

    private fun handleBindingDied()
    {
        handleServiceDisconnected()
        postIfOpen {
            if (bound)
            {
                try
                {
                    applicationContext.unbindService(serviceConnection)
                }
                catch (_: IllegalArgumentException)
                {
                    // Android may already have removed the dead binding.
                }
                bound = false
            }
            if (!closed)
            {
                bind()
            }
        }
    }

    private fun isActive(operationIdentifier: String): Boolean
    {
        return activeOperationIdentifier == operationIdentifier
    }

    private fun isCancelled(operationIdentifier: String): Boolean
    {
        return synchronized(clientLock)
        {
            operationIdentifier in cancelledOperationIdentifiers
        }
    }

    private fun failActiveOperation(message: String)
    {
        synchronized(clientLock)
        {
            activeOperationIdentifier = null
            cancelledOperationIdentifiers.clear()
        }
        mutableState.update { currentState ->
            currentState.afterOperationFailure(message)
        }
    }

    private fun clearOperationTranscripts(
        recordingState: ClientRecordingState,
        failureMessage: String?,
        connectionState: InferenceConnectionState = mutableState.value.connectionState,
        pcConnectionState: PcConnectionState = mutableState.value.pcConnectionState
    )
    {
        mutableState.update { currentState ->
            currentState.copy(
                connectionState = connectionState,
                pcConnectionState = pcConnectionState,
                recordingState = recordingState,
                normalizedAudioLevel = 0.0f,
                partialRawTranscript = "",
                finalRawTranscript = "",
                cleanTranscript = "",
                polishedTranscript = null,
                selectedTranscript = "",
                usedDeterministicFallback = false,
                completedOperationIdentifier = null,
                failureMessage = failureMessage
            )
        }
    }

    private fun postIfOpen(action: () -> Unit)
    {
        mainHandler.post {
            if (!closed)
            {
                action()
            }
        }
    }
}

/**
 * Returns the idle client state that is safe to expose after Android reconnects the inference Binder.
 *
 * A service disconnection abandons the active operation. Android can later reconnect the same long-lived client, so retaining ERROR would leave the UI unavailable even
 * after the replacement service has reported its model readiness.
 */
internal fun InferenceClientState.afterServiceConnected(): InferenceClientState
{
    return copy(
        connectionState = InferenceConnectionState.CONNECTED,
        pcConnectionState = PcConnectionState.CHECKING,
        recordingState = ClientRecordingState.IDLE,
        normalizedAudioLevel = 0.0f,
        partialRawTranscript = "",
        finalRawTranscript = "",
        cleanTranscript = "",
        polishedTranscript = null,
        selectedTranscript = "",
        usedDeterministicFallback = false,
        completedOperationIdentifier = null,
        failureMessage = null
    )
}

/**
 * Returns a retryable idle state after one dictation operation fails while the service and model remain available.
 */
internal fun InferenceClientState.afterOperationFailure(message: String): InferenceClientState
{
    return copy(
        recordingState = ClientRecordingState.IDLE,
        normalizedAudioLevel = 0.0f,
        partialRawTranscript = "",
        finalRawTranscript = "",
        cleanTranscript = "",
        polishedTranscript = null,
        selectedTranscript = "",
        usedDeterministicFallback = false,
        completedOperationIdentifier = null,
        failureMessage = message
    )
}

private fun parsePcConnectionState(code: Int): PcConnectionState
{
    return when (code)
    {
        InferenceProtocolCodes.PC_CONNECTION_CONNECTED -> PcConnectionState.CONNECTED
        InferenceProtocolCodes.PC_CONNECTION_DISCONNECTED -> PcConnectionState.DISCONNECTED
        else -> PcConnectionState.CHECKING
    }
}

private fun parseSpeechModelState(code: Int): SpeechModelState
{
    return when (code)
    {
        InferenceProtocolCodes.MODEL_VERIFYING_AND_LOADING -> SpeechModelState.VERIFYING_AND_LOADING
        InferenceProtocolCodes.MODEL_READY -> SpeechModelState.READY
        InferenceProtocolCodes.MODEL_FAILED -> SpeechModelState.FAILED
        else -> SpeechModelState.NOT_PREPARED
    }
}

private fun parseRecordingState(code: Int): ClientRecordingState
{
    return when (code)
    {
        InferenceProtocolCodes.RECORDING_PREPARING -> ClientRecordingState.PREPARING
        InferenceProtocolCodes.RECORDING_LISTENING -> ClientRecordingState.LISTENING
        InferenceProtocolCodes.RECORDING_SPEECH_DETECTED -> ClientRecordingState.SPEECH_DETECTED
        InferenceProtocolCodes.RECORDING_FINALIZING -> ClientRecordingState.FINALIZING
        else -> ClientRecordingState.ERROR
    }
}

private fun parseTranscriptMode(code: Int): TranscriptMode
{
    return when (code)
    {
        InferenceProtocolCodes.TRANSCRIPT_MODE_CLEAN -> TranscriptMode.CLEAN
        InferenceProtocolCodes.TRANSCRIPT_MODE_POLISHED -> TranscriptMode.POLISHED
        else -> TranscriptMode.RAW
    }
}

private fun TranscriptMode.toProtocolCode(): Int
{
    return when (this)
    {
        TranscriptMode.RAW -> InferenceProtocolCodes.TRANSCRIPT_MODE_RAW
        TranscriptMode.CLEAN -> InferenceProtocolCodes.TRANSCRIPT_MODE_CLEAN
        TranscriptMode.POLISHED -> InferenceProtocolCodes.TRANSCRIPT_MODE_POLISHED
    }
}

private fun OperationPrivacy.toProtocolCode(): Int
{
    return when (this)
    {
        OperationPrivacy.STANDARD -> InferenceProtocolCodes.PRIVACY_STANDARD
        OperationPrivacy.PRIVATE -> InferenceProtocolCodes.PRIVACY_PRIVATE
    }
}

private fun failureMessage(code: Int): String
{
    return when (code)
    {
        InferenceProtocolCodes.FAILURE_MODEL_UNAVAILABLE -> "The paired PC is unavailable."
        InferenceProtocolCodes.FAILURE_SPEECH_ENGINE -> "PC transcription failed."
        InferenceProtocolCodes.FAILURE_SERVICE_CLOSED -> "The recording service is shutting down."
        InferenceProtocolCodes.FAILURE_FOREGROUND_NOT_AUTHORIZED ->
            "Android did not authorize foreground microphone capture."
        else -> "The recording request was invalid."
    }
}
