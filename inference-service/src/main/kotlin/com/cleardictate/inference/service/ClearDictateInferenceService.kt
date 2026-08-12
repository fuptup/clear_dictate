package com.cleardictate.inference.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Build
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.cleardictate.domain.TranscriptMode
import com.cleardictate.domain.ProcessedTranscript
import com.cleardictate.inference.ClientSessionIdentifier
import com.cleardictate.inference.InferenceOperationContext
import com.cleardictate.inference.OperationIdentifier
import com.cleardictate.inference.OperationPrivacy
import com.cleardictate.inference.service.ipc.IClearDictateInferenceCallback
import com.cleardictate.inference.service.ipc.IClearDictateInferenceService
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs foreground microphone capture and paired-PC inference in a private process shared by the app and keyboard.
 */
class ClearDictateInferenceService : Service()
{
    private val mainHandler = Handler(Looper.getMainLooper())
    private val clientRegistrations =
        SerializedClientRegistrationRegistry<ClientSessionIdentifier, ClientRegistration>()
    private val foregroundLock = Any()
    private val pendingTerminalRegistry = PendingOperationTerminalRegistry()
    private val registrationGeneration = AtomicLong(0L)
    private lateinit var coordinator: InferenceCoordinator
    private lateinit var endpointPreferences: PcEndpointPreferences
    private lateinit var pcConnectionMonitor: PcConnectionMonitor
    private var foregroundOperation: ForegroundOperation? = null

    private val binder = object : IClearDictateInferenceService.Stub()
    {
        override fun registerClient(
            clientSessionIdentifier: String,
            callback: IClearDictateInferenceCallback
        )
        {
            val validatedClientIdentifier = parseClientIdentifier(clientSessionIdentifier) ?: return
            registerBinderClient(validatedClientIdentifier, callback)
        }

        override fun unregisterClient(clientSessionIdentifier: String)
        {
            val validatedClientIdentifier = parseClientIdentifier(clientSessionIdentifier) ?: return
            unregisterBinderClient(validatedClientIdentifier)
        }

        override fun configurePcEndpoint(clientSessionIdentifier: String, baseUrl: String, authorizationToken: String): Boolean
        {
            if (findEndpoint(clientSessionIdentifier) == null)
            {
                return false
            }
            return runCatching {
                endpointPreferences.save(baseUrl, authorizationToken)
                pcConnectionMonitor.refreshNow()
                coordinator.prepareSpeechModel()
                true
            }.getOrDefault(false)
        }

        override fun prepareSpeechModel()
        {
            coordinator.prepareSpeechModel()
        }

        override fun beginDictation(
            clientSessionIdentifier: String,
            operationIdentifier: String,
            privacyCode: Int
        )
        {
            if (!isForegroundAuthorized(clientSessionIdentifier, operationIdentifier))
            {
                val operation = parseOperationIdentifier(operationIdentifier) ?: return
                findEndpoint(clientSessionIdentifier)?.notifyForegroundNotAuthorized(operation)
                return
            }

            beginAuthorizedDictation(
                clientSessionIdentifier,
                operationIdentifier,
                privacyCode
            )
        }

        override fun stopDictation(clientSessionIdentifier: String, operationIdentifier: String)
        {
            var result = coordinator.stop(clientSessionIdentifier, operationIdentifier)

            if (result == StopDictationResult.OPERATION_NOT_ACTIVE &&
                findEndpoint(clientSessionIdentifier) != null)
            {
                pendingTerminalRegistry.recordStop(clientSessionIdentifier, operationIdentifier)
                result = coordinator.stop(clientSessionIdentifier, operationIdentifier)

                if (result == StopDictationResult.STOP_ACCEPTED)
                {
                    pendingTerminalRegistry.consume(clientSessionIdentifier, operationIdentifier)
                }
            }

            if (result == StopDictationResult.STOP_ACCEPTED)
            {
                findEndpoint(clientSessionIdentifier)?.onRecordingFinalizing(operationIdentifier)
            }
        }

        override fun cancelDictation(clientSessionIdentifier: String, operationIdentifier: String)
        {
            var result = coordinator.cancel(clientSessionIdentifier, operationIdentifier)

            if (result == CancelDictationResult.OPERATION_NOT_ACTIVE &&
                findEndpoint(clientSessionIdentifier) != null)
            {
                pendingTerminalRegistry.recordCancel(clientSessionIdentifier, operationIdentifier)
                result = coordinator.cancel(clientSessionIdentifier, operationIdentifier)

                if (result == CancelDictationResult.CANCELLATION_ACCEPTED)
                {
                    pendingTerminalRegistry.consume(clientSessionIdentifier, operationIdentifier)
                }
            }
        }
    }

    private fun beginAuthorizedDictation(
        clientSessionIdentifier: String,
        operationIdentifier: String,
        privacyCode: Int
    ): BeginDictationResult?
    {
        val clientIdentifier = parseClientIdentifier(clientSessionIdentifier) ?: return null
        val operation = parseOperationIdentifier(operationIdentifier)
        val privacy = parsePrivacy(privacyCode)
        val endpoint = clientRegistrations.find(clientIdentifier)?.endpoint ?: return null

        if (operation == null || privacy == null)
        {
            endpoint.notifyInvalidRequest(operationIdentifier)
            endForegroundOperation(clientSessionIdentifier, operationIdentifier)
            return null
        }

        endpoint.recordingOperation = operation
        endpoint.onRecordingPreparing(operation)
        val result = coordinator.beginDictation(
            BeginDictationRequest(
                operationContext = InferenceOperationContext(
                    clientSessionIdentifier = clientIdentifier,
                    operationIdentifier = operation,
                    privacy = privacy
                ),
                // The PC has already produced the selected polished text; local processing must not rewrite it again.
                transcriptMode = TranscriptMode.RAW
            )
        )

        if (result != BeginDictationResult.ACCEPTED)
        {
            endpoint.handleBeginRejection(operation, result)
            endForegroundOperation(clientSessionIdentifier, operationIdentifier)
        }
        return result
    }

    override fun onCreate()
    {
        super.onCreate()

        if (PROCESS_SHUTTING_DOWN.get())
        {
            android.os.Process.killProcess(android.os.Process.myPid())
            return
        }

        createNotificationChannel()
        endpointPreferences = PcEndpointPreferences(this)
        val pcTransport = PcDictationClient()
        coordinator = InferenceCoordinator(
            verifiedSpeechModelProvider = PcEndpointVerifiedSpeechModelProvider(endpointPreferences, pcTransport),
            streamingSpeechEngineFactory = PcStreamingSpeechEngineFactory(
                audioSourceFactory = AndroidPcmAudioSourceFactory(this),
                endpointProvider = endpointPreferences,
                transport = pcTransport
            ),
            fatalNativeFailureHandler = {
                mainHandler.post {
                    endAnyForegroundOperation()
                    android.os.Process.killProcess(android.os.Process.myPid())
                }
            }
        )
        pcConnectionMonitor = PcConnectionMonitor(
            endpointProvider = endpointPreferences,
            transport = pcTransport,
            pollIntervalMilliseconds = PC_CONNECTION_POLL_INTERVAL_MILLISECONDS,
            stateChanged = { state -> handlePcConnectionStateChanged(state) }
        )
        pcConnectionMonitor.start()
    }

    override fun onBind(intent: Intent?): IBinder?
    {
        return if (PROCESS_SHUTTING_DOWN.get() || !::coordinator.isInitialized)
        {
            null
        }
        else
        {
            binder
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int
    {
        if (PROCESS_SHUTTING_DOWN.get() || !::coordinator.isInitialized)
        {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        if (intent?.action != ACTION_PROMOTE_FOR_MICROPHONE)
        {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val clientIdentifier = intent.getStringExtra(EXTRA_CLIENT_IDENTIFIER).orEmpty()
        val operationIdentifier = intent.getStringExtra(EXTRA_OPERATION_IDENTIFIER).orEmpty()
        val privacyCode = intent.getIntExtra(
            EXTRA_PRIVACY,
            InferenceProtocolCodes.PRIVACY_STANDARD
        )

        if (parseClientIdentifier(clientIdentifier) == null ||
            parseOperationIdentifier(operationIdentifier) == null ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED))
        {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val pendingActionBeforeForeground =
            pendingTerminalRegistry.consume(clientIdentifier, operationIdentifier)

        if (pendingActionBeforeForeground == PendingOperationTerminalAction.CANCEL)
        {
            parseOperationIdentifier(operationIdentifier)?.let { operation ->
                findEndpoint(clientIdentifier)?.onOperationCancelled(operation)
            }
            stopSelf(startId)
            return START_NOT_STICKY
        }

        synchronized(foregroundLock)
        {
            val currentForegroundOperation = foregroundOperation

            if (currentForegroundOperation != null &&
                (currentForegroundOperation.clientIdentifier != clientIdentifier ||
                    currentForegroundOperation.operationIdentifier != operationIdentifier))
            {
                val requestedOperation = parseOperationIdentifier(operationIdentifier)

                if (requestedOperation != null)
                {
                    findEndpoint(clientIdentifier)?.onOperationBusy(requestedOperation)
                }
                return START_NOT_STICKY
            }

            foregroundOperation = ForegroundOperation(clientIdentifier, operationIdentifier)
        }

        try
        {
            ServiceCompat.startForeground(
                this,
                MICROPHONE_NOTIFICATION_IDENTIFIER,
                buildMicrophoneNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        }
        catch (_: RuntimeException)
        {
            parseOperationIdentifier(operationIdentifier)?.let { operation ->
                findEndpoint(clientIdentifier)?.notifyForegroundNotAuthorized(operation)
            }
            endForegroundOperation(clientIdentifier, operationIdentifier)
            return START_NOT_STICKY
        }
        scheduleUnusedForegroundTimeout(clientIdentifier, operationIdentifier)
        val beginResult = beginAuthorizedDictation(
            clientIdentifier,
            operationIdentifier,
            privacyCode
        )

        if (beginResult == BeginDictationResult.ACCEPTED)
        {
            val pendingActionAfterBegin =
                pendingTerminalRegistry.consume(clientIdentifier, operationIdentifier)
                    ?: pendingActionBeforeForeground

            when (pendingActionAfterBegin)
            {
                PendingOperationTerminalAction.CANCEL ->
                    coordinator.cancel(clientIdentifier, operationIdentifier)
                PendingOperationTerminalAction.STOP ->
                {
                    if (coordinator.stop(clientIdentifier, operationIdentifier) == StopDictationResult.STOP_ACCEPTED)
                    {
                        findEndpoint(clientIdentifier)?.onRecordingFinalizing(operationIdentifier)
                    }
                }
                null -> Unit
            }
        }
        else
        {
            pendingTerminalRegistry.consume(clientIdentifier, operationIdentifier)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy()
    {
        PROCESS_SHUTTING_DOWN.set(true)
        if (::pcConnectionMonitor.isInitialized)
        {
            pcConnectionMonitor.close()
        }
        val registrations = clientRegistrations.drain()
        registrations.forEach { registration ->
            registration.callback.asBinder().unlinkToDeath(registration.deathRecipient, 0)
        }
        endAnyForegroundOperation()

        Thread(
            {
                try
                {
                    if (::coordinator.isInitialized)
                    {
                        coordinator.close()
                    }
                }
                finally
                {
                    // This private process contains no application user interface or persistent state.
                    // Terminating it is the only safe containment boundary for an uninterruptible native call.
                    android.os.Process.killProcess(android.os.Process.myPid())
                }
            },
            "cleardictate-service-disposal"
        ).apply {
            isDaemon = false
            start()
        }
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int)
    {
        super.onTrimMemory(level)

        if (::coordinator.isInitialized && level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
        {
            coordinator.releaseIdleModelsForMemoryPressure()
        }
    }

    override fun onLowMemory()
    {
        if (::coordinator.isInitialized)
        {
            coordinator.releaseIdleModelsForMemoryPressure()
        }
        super.onLowMemory()
    }

    private fun registerBinderClient(
        clientIdentifier: ClientSessionIdentifier,
        callback: IClearDictateInferenceCallback
    )
    {
        val generation = registrationGeneration.incrementAndGet()
        val endpoint = BinderInferenceClientEndpoint(
            callback = callback,
            audioCaptureFinishedHandler = { operationIdentifier ->
                mainHandler.post {
                    endForegroundOperation(clientIdentifier.value, operationIdentifier.value)
                }
            },
            terminalOperationHandler = { operationIdentifier ->
                mainHandler.post {
                    endForegroundOperation(clientIdentifier.value, operationIdentifier.value)
                }
            }
        )
        val deathRecipient = IBinder.DeathRecipient {
            unregisterBinderClient(clientIdentifier, generation)
        }

        try
        {
            callback.asBinder().linkToDeath(deathRecipient, 0)
        }
        catch (_: Exception)
        {
            return
        }

        val registration = ClientRegistration(
            callback = callback,
            endpoint = endpoint,
            deathRecipient = deathRecipient,
            generation = generation
        )
        clientRegistrations.replace(
            clientKey = clientIdentifier,
            registration = registration,
            activate = {
                if (!callback.asBinder().isBinderAlive)
                {
                    false
                }
                else
                {
                    coordinator.registerClient(clientIdentifier, endpoint)
                    endpoint.onPcConnectionStateChanged(pcConnectionMonitor.currentState)
                    callback.asBinder().isBinderAlive
                }
            },
            deactivate = { replacedRegistration ->
                deactivateClientRegistration(clientIdentifier, replacedRegistration)
            }
        )
    }

    private fun unregisterBinderClient(clientIdentifier: ClientSessionIdentifier)
    {
        unregisterBinderClient(clientIdentifier, expectedGeneration = null)
    }

    private fun unregisterBinderClient(clientIdentifier: ClientSessionIdentifier, expectedGeneration: Long?)
    {
        clientRegistrations.remove(
            clientKey = clientIdentifier,
            expectedRegistration = { registration ->
                expectedGeneration == null || registration.generation == expectedGeneration
            },
            deactivate = { registration ->
                deactivateClientRegistration(clientIdentifier, registration)
            }
        )
    }

    /**
     * Broadcasts one authoritative PC-availability state to every bound surface and retries model preparation after connectivity recovers.
     */
    private fun handlePcConnectionStateChanged(state: PcConnectionState)
    {
        clientRegistrations.snapshot().forEach { registration ->
            registration.endpoint.onPcConnectionStateChanged(state)
        }

        if (state == PcConnectionState.CONNECTED &&
            coordinator.currentSpeechModelState() != SpeechModelState.READY &&
            coordinator.currentSpeechModelState() != SpeechModelState.VERIFYING_AND_LOADING)
        {
            coordinator.prepareSpeechModel()
        }
    }

    private fun deactivateClientRegistration(
        clientIdentifier: ClientSessionIdentifier,
        registration: ClientRegistration
    )
    {
        try
        {
            registration.callback.asBinder().unlinkToDeath(registration.deathRecipient, 0)
        }
        finally
        {
            coordinator.unregisterClient(clientIdentifier)
            pendingTerminalRegistry.clearClient(clientIdentifier.value)
            registration.endpoint.recordingOperation?.let { operationIdentifier ->
                endForegroundOperation(clientIdentifier.value, operationIdentifier.value)
            }
        }
    }

    private fun findEndpoint(clientIdentifier: String): BinderInferenceClientEndpoint?
    {
        val validatedIdentifier = parseClientIdentifier(clientIdentifier) ?: return null
        return clientRegistrations.find(validatedIdentifier)?.endpoint
    }

    private fun isForegroundAuthorized(clientIdentifier: String, operationIdentifier: String): Boolean
    {
        return synchronized(foregroundLock)
        {
            foregroundOperation == ForegroundOperation(clientIdentifier, operationIdentifier)
        }
    }

    private fun scheduleUnusedForegroundTimeout(clientIdentifier: String, operationIdentifier: String)
    {
        mainHandler.postDelayed(
            {
                val operationHasStarted = findEndpoint(clientIdentifier)
                    ?.recordingOperation
                    ?.value == operationIdentifier

                if (!operationHasStarted)
                {
                    endForegroundOperation(clientIdentifier, operationIdentifier)
                }
            },
            FOREGROUND_AUTHORIZATION_TIMEOUT_MILLISECONDS
        )
    }

    private fun endForegroundOperation(clientIdentifier: String, operationIdentifier: String)
    {
        pendingTerminalRegistry.consume(clientIdentifier, operationIdentifier)
        val shouldStop = synchronized(foregroundLock)
        {
            if (foregroundOperation == ForegroundOperation(clientIdentifier, operationIdentifier))
            {
                foregroundOperation = null
                true
            }
            else
            {
                false
            }
        }

        if (shouldStop)
        {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun endAnyForegroundOperation()
    {
        synchronized(foregroundLock)
        {
            foregroundOperation = null
        }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    private fun createNotificationChannel()
    {
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(
            NotificationChannel(
                MICROPHONE_NOTIFICATION_CHANNEL,
                getString(R.string.inference_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.inference_notification_channel_description)
                setSound(null, null)
                enableVibration(false)
            }
        )
    }

    private fun buildMicrophoneNotification(): Notification
    {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val contentIntent = launchIntent?.let { intent ->
            PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }

        return NotificationCompat.Builder(this, MICROPHONE_NOTIFICATION_CHANNEL)
            .setSmallIcon(R.drawable.ic_cleardictate_microphone)
            .setContentTitle(getString(R.string.inference_notification_title))
            .setContentText(getString(R.string.inference_notification_text))
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .build()
    }

    private fun parseClientIdentifier(value: String): ClientSessionIdentifier?
    {
        return try
        {
            ClientSessionIdentifier(value)
        }
        catch (_: IllegalArgumentException)
        {
            null
        }
    }

    private fun parseOperationIdentifier(value: String): OperationIdentifier?
    {
        return try
        {
            OperationIdentifier(value)
        }
        catch (_: IllegalArgumentException)
        {
            null
        }
    }

    private fun parsePrivacy(code: Int): OperationPrivacy?
    {
        return when (code)
        {
            InferenceProtocolCodes.PRIVACY_STANDARD -> OperationPrivacy.STANDARD
            InferenceProtocolCodes.PRIVACY_PRIVATE -> OperationPrivacy.PRIVATE
            else -> null
        }
    }

    private data class ClientRegistration(
        val callback: IClearDictateInferenceCallback,
        val endpoint: BinderInferenceClientEndpoint,
        val deathRecipient: IBinder.DeathRecipient,
        val generation: Long
    )

    private data class ForegroundOperation(
        val clientIdentifier: String,
        val operationIdentifier: String
    )

    companion object
    {
        private const val ACTION_PROMOTE_FOR_MICROPHONE =
            "com.cleardictate.inference.service.action.PROMOTE_FOR_MICROPHONE"
        private const val EXTRA_CLIENT_IDENTIFIER = "client_session_identifier"
        private const val EXTRA_OPERATION_IDENTIFIER = "operation_identifier"
        private const val EXTRA_PRIVACY = "privacy"
        private const val MICROPHONE_NOTIFICATION_CHANNEL = "active_dictation"
        private const val MICROPHONE_NOTIFICATION_IDENTIFIER = 4101
        private const val FOREGROUND_AUTHORIZATION_TIMEOUT_MILLISECONDS = 10_000L
        private const val PC_CONNECTION_POLL_INTERVAL_MILLISECONDS = 30_000L
        private val PROCESS_SHUTTING_DOWN = AtomicBoolean(false)

        fun createMicrophoneForegroundIntent(
            context: Context,
            clientSessionIdentifier: String,
            operationIdentifier: String,
            privacy: OperationPrivacy
        ): Intent
        {
            return Intent(context, ClearDictateInferenceService::class.java).apply {
                action = ACTION_PROMOTE_FOR_MICROPHONE
                putExtra(EXTRA_CLIENT_IDENTIFIER, clientSessionIdentifier)
                putExtra(EXTRA_OPERATION_IDENTIFIER, operationIdentifier)
                putExtra(EXTRA_PRIVACY, privacy.toProtocolCode())
            }
        }
    }
}

private class BinderInferenceClientEndpoint(
    private val callback: IClearDictateInferenceCallback,
    private val audioCaptureFinishedHandler: (OperationIdentifier) -> Unit,
    private val terminalOperationHandler: (OperationIdentifier) -> Unit
) : InferenceClientEndpoint
{
    @Volatile
    var recordingOperation: OperationIdentifier? = null

    fun onPcConnectionStateChanged(state: PcConnectionState)
    {
        safelyDeliver { callback.onPcConnectionStateChanged(state.toProtocolCode()) }
    }

    override fun onSpeechModelStateChanged(state: SpeechModelState)
    {
        safelyDeliver { callback.onSpeechModelStateChanged(state.toProtocolCode()) }
    }

    override fun onOperationAccepted(operationIdentifier: OperationIdentifier)
    {
        safelyDeliver { callback.onOperationAccepted(operationIdentifier.value) }
    }

    override fun onOperationBusy(operationIdentifier: OperationIdentifier)
    {
        safelyDeliver { callback.onOperationBusy(operationIdentifier.value) }
    }

    override fun onPartialTranscript(operationIdentifier: OperationIdentifier, rawPartialTranscript: String)
    {
        safelyDeliver { callback.onPartialTranscript(operationIdentifier.value, rawPartialTranscript) }
    }

    override fun onRecordingStarted(operationIdentifier: OperationIdentifier)
    {
        safelyDeliver {
            callback.onRecordingStateChanged(
                operationIdentifier.value,
                InferenceProtocolCodes.RECORDING_LISTENING
            )
        }
    }

    override fun onSpeechDetected(operationIdentifier: OperationIdentifier)
    {
        safelyDeliver {
            callback.onRecordingStateChanged(
                operationIdentifier.value,
                InferenceProtocolCodes.RECORDING_SPEECH_DETECTED
            )
        }
    }

    override fun onAudioLevel(operationIdentifier: OperationIdentifier, normalizedLevel: Float)
    {
        safelyDeliver { callback.onAudioLevel(operationIdentifier.value, normalizedLevel) }
    }

    override fun onAudioCaptureFinished(operationIdentifier: OperationIdentifier)
    {
        audioCaptureFinishedHandler(operationIdentifier)
    }

    override fun onFinalTranscript(operationIdentifier: OperationIdentifier, processedTranscript: ProcessedTranscript)
    {
        try
        {
            val pcPolishedTranscript = processedTranscript.selectedTranscript
            safelyDeliver {
                callback.onFinalTranscript(
                    operationIdentifier.value,
                    "",
                    "",
                    pcPolishedTranscript,
                    pcPolishedTranscript,
                    TranscriptMode.POLISHED.toProtocolCode(),
                    false,
                    0
                )
            }
        }
        finally
        {
            terminal(operationIdentifier)
        }
    }

    override fun onOperationCancelled(operationIdentifier: OperationIdentifier)
    {
        try
        {
            safelyDeliver { callback.onOperationCancelled(operationIdentifier.value) }
        }
        finally
        {
            terminal(operationIdentifier)
        }
    }

    override fun onFailure(operationIdentifier: OperationIdentifier, failure: DictationFailure)
    {
        try
        {
            safelyDeliver { callback.onFailure(operationIdentifier.value, failure.toProtocolCode()) }
        }
        finally
        {
            terminal(operationIdentifier)
        }
    }

    fun onRecordingPreparing(operationIdentifier: OperationIdentifier)
    {
        safelyDeliver {
            callback.onRecordingStateChanged(
                operationIdentifier.value,
                InferenceProtocolCodes.RECORDING_PREPARING
            )
        }
    }

    fun onRecordingFinalizing(operationIdentifier: String)
    {
        safelyDeliver {
            callback.onRecordingStateChanged(
                operationIdentifier,
                InferenceProtocolCodes.RECORDING_FINALIZING
            )
        }
    }

    fun notifyInvalidRequest(operationIdentifier: String)
    {
        safelyDeliver { callback.onFailure(operationIdentifier, InferenceProtocolCodes.FAILURE_INVALID_REQUEST) }
    }

    fun notifyForegroundNotAuthorized(operationIdentifier: OperationIdentifier)
    {
        safelyDeliver {
            callback.onFailure(
                operationIdentifier.value,
                InferenceProtocolCodes.FAILURE_FOREGROUND_NOT_AUTHORIZED
            )
        }
    }

    fun handleBeginRejection(operationIdentifier: OperationIdentifier, result: BeginDictationResult)
    {
        if (recordingOperation == operationIdentifier)
        {
            recordingOperation = null
        }

        when (result)
        {
            BeginDictationResult.BUSY -> Unit
            BeginDictationResult.SPEECH_MODEL_NOT_READY ->
                safelyDeliver {
                    callback.onFailure(operationIdentifier.value, InferenceProtocolCodes.FAILURE_MODEL_UNAVAILABLE)
                }
            BeginDictationResult.SERVICE_CLOSED ->
                safelyDeliver {
                    callback.onFailure(operationIdentifier.value, InferenceProtocolCodes.FAILURE_SERVICE_CLOSED)
                }
            BeginDictationResult.CLIENT_NOT_REGISTERED,
            BeginDictationResult.ACCEPTED ->
                safelyDeliver {
                    callback.onFailure(operationIdentifier.value, InferenceProtocolCodes.FAILURE_INVALID_REQUEST)
                }
        }
    }

    private fun terminal(operationIdentifier: OperationIdentifier)
    {
        if (recordingOperation == operationIdentifier)
        {
            recordingOperation = null
        }
        terminalOperationHandler(operationIdentifier)
    }

    private fun safelyDeliver(delivery: () -> Unit)
    {
        try
        {
            delivery()
        }
        catch (_: Exception)
        {
            // Client death must not escape onto the service main thread or block local cleanup.
        }
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

private fun SpeechModelState.toProtocolCode(): Int
{
    return when (this)
    {
        SpeechModelState.NOT_PREPARED -> InferenceProtocolCodes.MODEL_NOT_PREPARED
        SpeechModelState.VERIFYING_AND_LOADING -> InferenceProtocolCodes.MODEL_VERIFYING_AND_LOADING
        SpeechModelState.READY -> InferenceProtocolCodes.MODEL_READY
        SpeechModelState.FAILED -> InferenceProtocolCodes.MODEL_FAILED
    }
}

private fun PcConnectionState.toProtocolCode(): Int
{
    return when (this)
    {
        PcConnectionState.CHECKING -> InferenceProtocolCodes.PC_CONNECTION_CHECKING
        PcConnectionState.CONNECTED -> InferenceProtocolCodes.PC_CONNECTION_CONNECTED
        PcConnectionState.DISCONNECTED -> InferenceProtocolCodes.PC_CONNECTION_DISCONNECTED
    }
}

private fun DictationFailure.toProtocolCode(): Int
{
    return when (this)
    {
        DictationFailure.MODEL_UNAVAILABLE -> InferenceProtocolCodes.FAILURE_MODEL_UNAVAILABLE
        DictationFailure.SPEECH_ENGINE_FAILURE -> InferenceProtocolCodes.FAILURE_SPEECH_ENGINE
        DictationFailure.SERVICE_CLOSED -> InferenceProtocolCodes.FAILURE_SERVICE_CLOSED
    }
}
