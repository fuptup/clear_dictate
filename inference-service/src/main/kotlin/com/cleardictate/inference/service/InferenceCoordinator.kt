package com.cleardictate.inference.service

import com.cleardictate.domain.StreamingTranscriptAccumulator
import com.cleardictate.domain.ProcessedTranscript
import com.cleardictate.domain.TranscriptFallbackReason
import com.cleardictate.domain.TranscriptMode
import com.cleardictate.domain.TranscriptPolisher
import com.cleardictate.domain.TranscriptPolishingRequest
import com.cleardictate.domain.TranscriptProcessingPipeline
import com.cleardictate.inference.CancellationAcknowledgement
import com.cleardictate.inference.ClientSessionIdentifier
import com.cleardictate.inference.InferenceFailureCategory
import com.cleardictate.inference.InferenceOperationContext
import com.cleardictate.inference.LocalInferenceException
import com.cleardictate.inference.OperationIdentifier
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking

/**
 * Contains the immutable information needed to begin one local dictation operation.
 */
data class BeginDictationRequest(
    val operationContext: InferenceOperationContext,
    val transcriptMode: TranscriptMode
)

enum class BeginDictationResult
{
    ACCEPTED,
    BUSY,
    SPEECH_MODEL_NOT_READY,
    CLIENT_NOT_REGISTERED,
    SERVICE_CLOSED
}

enum class SpeechModelState
{
    NOT_PREPARED,
    VERIFYING_AND_LOADING,
    READY,
    FAILED
}

enum class CancelDictationResult
{
    CANCELLATION_ACCEPTED,
    OPERATION_NOT_ACTIVE,
    CLIENT_DOES_NOT_OWN_OPERATION
}

enum class StopDictationResult
{
    STOP_ACCEPTED,
    OPERATION_NOT_ACTIVE,
    CLIENT_DOES_NOT_OWN_OPERATION
}

/**
 * Provides transcript-free failure categories that are safe to cross the process boundary.
 */
enum class DictationFailure
{
    MODEL_UNAVAILABLE,
    SPEECH_ENGINE_FAILURE,
    SERVICE_CLOSED
}

/**
 * Receives only events belonging to the client that registered this endpoint.
 */
interface InferenceClientEndpoint
{
    fun onOperationAccepted(operationIdentifier: OperationIdentifier)

    fun onOperationBusy(operationIdentifier: OperationIdentifier)

    fun onSpeechModelStateChanged(state: SpeechModelState)
    {
    }

    fun onPartialTranscript(operationIdentifier: OperationIdentifier, rawPartialTranscript: String)

    fun onRecordingStarted(operationIdentifier: OperationIdentifier)
    {
    }

    fun onSpeechDetected(operationIdentifier: OperationIdentifier)
    {
    }

    fun onAudioLevel(operationIdentifier: OperationIdentifier, normalizedLevel: Float)
    {
    }

    fun onAudioCaptureFinished(operationIdentifier: OperationIdentifier)
    {
    }

    fun onFinalTranscript(operationIdentifier: OperationIdentifier, processedTranscript: ProcessedTranscript)
    {
    }

    fun onOperationCancelled(operationIdentifier: OperationIdentifier)

    fun onFailure(operationIdentifier: OperationIdentifier, failure: DictationFailure)
}

/**
 * Allows cancellation to become visible without waiting behind model loading or native inference.
 */
class InferenceCancellationSignal
{
    private val cancelledState = AtomicBoolean(false)

    val isCancellationRequested: Boolean
        get() = cancelledState.get()

    fun requestCancellation()
    {
        cancelledState.set(true)
    }
}

/**
 * Retains the verified model artifacts for exactly as long as the native engine can reference them.
 */
interface VerifiedSpeechModelLease : AutoCloseable
{
    val verifiedModelDirectoryPath: String
}

/**
 * Acquires one all-files-verified Moonshine model lease before native parsing is permitted.
 */
fun interface VerifiedSpeechModelProvider
{
    fun acquireVerifiedModel(cancellationSignal: InferenceCancellationSignal): VerifiedSpeechModelLease
}

/**
 * Receives immutable, operation-scoped speech events from the serialized native engine.
 */
interface StreamingSpeechEventListener
{
    fun onPartial(lineIdentifier: Long, text: String)

    fun onCompleted(lineIdentifier: Long, text: String)

    fun onSpeechDetected()
    {
    }

    fun onAudioLevel(normalizedLevel: Float)
    {
    }

    fun onFailure()
}

/**
 * Isolates the stateful native streaming engine and its cancellation control path.
 */
interface StreamingSpeechEngine : AutoCloseable
{
    fun start(listener: StreamingSpeechEventListener)

    fun stopAndFlush()

    /**
     * Sets the native cancellation flag without waiting for the serialized native worker.
     */
    fun requestCancellation()

    /**
     * Runs on the serialized native worker after cancellation has been requested.
     */
    fun cancelAndDrain()
    {
    }
}

fun interface StreamingSpeechEngineFactory
{
    fun open(verifiedModelLease: VerifiedSpeechModelLease): StreamingSpeechEngine
}

/**
 * Owns the sole speech-model instance and arbitrates all application and keyboard clients.
 *
 * Public methods do bounded coordination only. Model verification, native opening, start, stop,
 * and disposal run on one private worker so no native handle is used concurrently.
 */
class InferenceCoordinator(
    private val verifiedSpeechModelProvider: VerifiedSpeechModelProvider,
    private val streamingSpeechEngineFactory: StreamingSpeechEngineFactory,
    private val transcriptPolisher: TranscriptPolisher = UnavailableAndroidTranscriptPolisher,
    private val transcriptProcessingPipeline: TranscriptProcessingPipeline =
        TranscriptProcessingPipeline(polisher = transcriptPolisher),
    private val fatalNativeFailureHandler: () -> Unit = {},
    private val nativeWorker: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "cleardictate-native-speech").apply {
            isDaemon = true
        }
    },
    private val textCancellationWorker: ExecutorService =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "cleardictate-text-cancellation").apply {
                isDaemon = true
            }
        },
    private val cancellationWatchdog: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "cleardictate-cancellation-watchdog").apply {
                isDaemon = true
            }
        },
    private val cancellationWatchdogMilliseconds: Long = 3_000L,
    private val polishingCancellationMilliseconds: Long = 15_000L,
    private val polishingFatalWatchdogMilliseconds: Long = 17_000L
) : AutoCloseable
{
    private val coordinationLock = Any()
    private val clients = mutableMapOf<ClientSessionIdentifier, InferenceClientEndpoint>()
    private val transcriptAccumulator = StreamingTranscriptAccumulator()

    private var activeOperation: ActiveOperation? = null
    private var verifiedModelLease: VerifiedSpeechModelLease? = null
    private var speechEngine: StreamingSpeechEngine? = null
    private var speechModelState = SpeechModelState.NOT_PREPARED
    private var modelPreparationCancellationSignal: InferenceCancellationSignal? = null
    private var nativeProcessPoisoned = false
    private var closed = false
    private val fatalFailureReported = AtomicBoolean(false)

    init
    {
        require(cancellationWatchdogMilliseconds > 0L) {
            "The cancellation watchdog timeout must be positive."
        }
        require(polishingCancellationMilliseconds > 0L) {
            "The polishing cancellation timeout must be positive."
        }
        require(polishingFatalWatchdogMilliseconds > polishingCancellationMilliseconds) {
            "The fatal polishing watchdog must run after the cancellation request."
        }
    }

    /**
     * Replaces an earlier endpoint for the same opaque client identity.
     */
    fun registerClient(clientSessionIdentifier: ClientSessionIdentifier, endpoint: InferenceClientEndpoint)
    {
        val currentModelState: SpeechModelState

        synchronized(coordinationLock)
        {
            check(!closed) { "Cannot register a client after the inference coordinator is closed." }
            clients[clientSessionIdentifier] = endpoint
            currentModelState = speechModelState
        }

        safelyNotify {
            endpoint.onSpeechModelStateChanged(currentModelState)
        }
    }

    /**
     * Verifies and opens the speech model before a user requests microphone foreground execution.
     */
    fun prepareSpeechModel()
    {
        val cancellationSignal: InferenceCancellationSignal

        synchronized(coordinationLock)
        {
            if (closed ||
                nativeProcessPoisoned ||
                speechModelState == SpeechModelState.VERIFYING_AND_LOADING ||
                speechModelState == SpeechModelState.READY)
            {
                return
            }

            cancellationSignal = InferenceCancellationSignal()
            modelPreparationCancellationSignal = cancellationSignal
            speechModelState = SpeechModelState.VERIFYING_AND_LOADING
        }

        notifyModelState(SpeechModelState.VERIFYING_AND_LOADING)
        nativeWorker.execute {
            prepareSpeechModelOnNativeWorker(cancellationSignal)
        }
    }

    /**
     * Removes the endpoint and cancels any operation that it owned, matching Binder-death behavior.
     */
    fun unregisterClient(clientSessionIdentifier: ClientSessionIdentifier)
    {
        val operationToCancel = synchronized(coordinationLock)
        {
            clients.remove(clientSessionIdentifier)

            activeOperation?.takeIf { operation ->
                operation.request.operationContext.clientSessionIdentifier == clientSessionIdentifier
            }
        }

        cancelDetachedOperation(operationToCancel, notifyClient = false)
    }

    /**
     * Reserves global ownership synchronously and schedules all slow preparation on the native worker.
     */
    fun beginDictation(request: BeginDictationRequest): BeginDictationResult
    {
        val clientIdentifier = request.operationContext.clientSessionIdentifier
        val endpoint: InferenceClientEndpoint
        var newOperation: ActiveOperation? = null
        var busy = false

        synchronized(coordinationLock)
        {
            if (closed)
            {
                return BeginDictationResult.SERVICE_CLOSED
            }

            endpoint = clients[clientIdentifier]
                ?: return BeginDictationResult.CLIENT_NOT_REGISTERED

            if (activeOperation != null)
            {
                busy = true
            }
            else if (speechModelState != SpeechModelState.READY || speechEngine == null)
            {
                return BeginDictationResult.SPEECH_MODEL_NOT_READY
            }
            else
            {
                newOperation = ActiveOperation(
                    request = request,
                    endpoint = endpoint,
                    cancellationSignal = InferenceCancellationSignal(),
                    accumulatorSessionIdentifier = transcriptAccumulator.beginSession()
                )
                activeOperation = newOperation
            }
        }

        if (busy)
        {
            safelyNotify {
                endpoint.onOperationBusy(request.operationContext.operationIdentifier)
            }
            return BeginDictationResult.BUSY
        }

        val acceptedOperation = checkNotNull(newOperation)
        safelyNotify {
            endpoint.onOperationAccepted(request.operationContext.operationIdentifier)
        }
        nativeWorker.execute {
            prepareAndStart(acceptedOperation)
        }
        return BeginDictationResult.ACCEPTED
    }

    /**
     * Requests a flushed final transcript without blocking the calling Binder thread.
     */
    fun stop(clientIdentifier: String, operationIdentifier: String): StopDictationResult
    {
        val operation = findOwnedOperation(clientIdentifier, operationIdentifier)
            ?: return determineMissingStopResult(clientIdentifier, operationIdentifier)

        nativeWorker.execute {
            stopAndFinalize(operation)
        }
        return StopDictationResult.STOP_ACCEPTED
    }

    /**
     * Invalidates callback identity first, then reaches the independent engine cancellation path.
     */
    fun cancel(clientIdentifier: String, operationIdentifier: String): CancelDictationResult
    {
        val operationToCancel: ActiveOperation
        val engineToCancel: StreamingSpeechEngine?

        synchronized(coordinationLock)
        {
            val currentOperation = activeOperation
                ?: return CancelDictationResult.OPERATION_NOT_ACTIVE

            if (currentOperation.request.operationContext.clientSessionIdentifier.value != clientIdentifier ||
                currentOperation.request.operationContext.operationIdentifier.value != operationIdentifier)
            {
                return CancelDictationResult.CLIENT_DOES_NOT_OWN_OPERATION
            }

            operationToCancel = currentOperation
            engineToCancel = speechEngine
        }

        if (!operationToCancel.terminalCleanupScheduled.compareAndSet(false, true))
        {
            return CancelDictationResult.CANCELLATION_ACCEPTED
        }

        operationToCancel.cancellationSignal.requestCancellation()
        scheduleCancellationWatchdog(operationToCancel)
        engineToCancel?.requestCancellation()
        requestTextPolishingCancellation(operationToCancel)
        nativeWorker.execute {
            drainCancelledOperation(operationToCancel, engineToCancel, notifyClient = true)
        }
        return CancelDictationResult.CANCELLATION_ACCEPTED
    }

    fun hasActiveOperation(): Boolean
    {
        return synchronized(coordinationLock)
        {
            activeOperation != null
        }
    }

    fun currentSpeechModelState(): SpeechModelState
    {
        return synchronized(coordinationLock)
        {
            speechModelState
        }
    }

    /**
     * Cancels active work and releases native resources in reverse acquisition order.
     */
    override fun close()
    {
        val operationToCancel: ActiveOperation?
        val engineToCancel: StreamingSpeechEngine?
        val modelPreparationToCancel: InferenceCancellationSignal?

        synchronized(coordinationLock)
        {
            if (closed)
            {
                return
            }

            closed = true
            operationToCancel = activeOperation
            activeOperation = null
            clients.clear()
            engineToCancel = speechEngine
            modelPreparationToCancel = modelPreparationCancellationSignal
        }

        operationToCancel?.cancellationSignal?.requestCancellation()
        modelPreparationToCancel?.requestCancellation()
        operationToCancel?.let { operation ->
            transcriptAccumulator.cancelSession(operation.accumulatorSessionIdentifier)
        }
        operationToCancel?.let(::requestTextPolishingCancellation)

        nativeWorker.execute {
            releaseNativeResources()
        }
        nativeWorker.shutdown()
        textCancellationWorker.shutdown()
        cancellationWatchdog.shutdownNow()

        if (!nativeWorker.awaitTermination(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        {
            nativeWorker.shutdownNow()
        }
        if (!textCancellationWorker.awaitTermination(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        {
            textCancellationWorker.shutdownNow()
        }
    }

    private fun prepareAndStart(operation: ActiveOperation)
    {
        try
        {
            val engine = synchronized(coordinationLock)
            {
                speechEngine
            } ?: run {
                drainFailedOperation(operation, DictationFailure.MODEL_UNAVAILABLE, selectedEngine = null)
                return
            }

            if (!isCurrentOperation(operation))
            {
                engine.requestCancellation()
                return
            }

            engine.start(createSpeechEventListener(operation))

            if (isCurrentOperation(operation))
            {
                safelyNotify {
                    operation.endpoint.onRecordingStarted(
                        operation.request.operationContext.operationIdentifier
                    )
                }
            }

            if (operation.cancellationSignal.isCancellationRequested)
            {
                engine.requestCancellation()
                engine.cancelAndDrain()
            }
        }
        catch (_: Exception)
        {
            drainFailedOperation(operation, DictationFailure.SPEECH_ENGINE_FAILURE)
        }
        catch (_: LinkageError)
        {
            drainFailedOperation(operation, DictationFailure.SPEECH_ENGINE_FAILURE)
        }
    }

    private fun prepareSpeechModelOnNativeWorker(cancellationSignal: InferenceCancellationSignal)
    {
        try
        {
            val acquiredLease = verifiedSpeechModelProvider.acquireVerifiedModel(cancellationSignal)

            if (cancellationSignal.isCancellationRequested || isClosed())
            {
                acquiredLease.close()
                return
            }

            val openedEngine = try
            {
                streamingSpeechEngineFactory.open(acquiredLease)
            }
            catch (failure: Throwable)
            {
                acquiredLease.close()
                throw failure
            }

            if (cancellationSignal.isCancellationRequested || isClosed())
            {
                openedEngine.close()
                acquiredLease.close()
                return
            }

            synchronized(coordinationLock)
            {
                speechEngine = openedEngine
                verifiedModelLease = acquiredLease
                speechModelState = SpeechModelState.READY
                modelPreparationCancellationSignal = null
            }
            notifyModelState(SpeechModelState.READY)
        }
        catch (_: Throwable)
        {
            val shouldNotify = synchronized(coordinationLock)
            {
                if (closed || cancellationSignal.isCancellationRequested)
                {
                    false
                }
                else
                {
                    speechModelState = SpeechModelState.FAILED
                    modelPreparationCancellationSignal = null
                    true
                }
            }

            if (shouldNotify)
            {
                notifyModelState(SpeechModelState.FAILED)
            }
        }
    }

    private fun createSpeechEventListener(operation: ActiveOperation): StreamingSpeechEventListener
    {
        return object : StreamingSpeechEventListener
        {
            override fun onPartial(lineIdentifier: Long, text: String)
            {
                if (!isCurrentOperation(operation))
                {
                    return
                }

                if (transcriptAccumulator.acceptPartial(
                        operation.accumulatorSessionIdentifier,
                        lineIdentifier,
                        text
                    ))
                {
                    safelyNotify {
                        operation.endpoint.onPartialTranscript(
                            operation.request.operationContext.operationIdentifier,
                            transcriptAccumulator.snapshot().visibleRawTranscript
                        )
                    }
                }
                else if (transcriptAccumulator.hasExceededLimit(operation.accumulatorSessionIdentifier))
                {
                    handleSpeechEngineFailure(operation)
                }
            }

            override fun onCompleted(lineIdentifier: Long, text: String)
            {
                if (!isCurrentOperation(operation))
                {
                    return
                }

                val accepted = transcriptAccumulator.acceptCompleted(
                    operation.accumulatorSessionIdentifier,
                    lineIdentifier,
                    text
                )

                if (!accepted &&
                    transcriptAccumulator.hasExceededLimit(operation.accumulatorSessionIdentifier))
                {
                    handleSpeechEngineFailure(operation)
                }
            }

            override fun onSpeechDetected()
            {
                if (isCurrentOperation(operation))
                {
                    safelyNotify {
                        operation.endpoint.onSpeechDetected(
                            operation.request.operationContext.operationIdentifier
                        )
                    }
                }
            }

            override fun onAudioLevel(normalizedLevel: Float)
            {
                if (isCurrentOperation(operation))
                {
                    safelyNotify {
                        operation.endpoint.onAudioLevel(
                            operation.request.operationContext.operationIdentifier,
                            normalizedLevel
                        )
                    }
                }
            }

            override fun onFailure()
            {
                handleSpeechEngineFailure(operation)
            }
        }
    }

    private fun handleSpeechEngineFailure(operation: ActiveOperation)
    {
        val engineToCancel = synchronized(coordinationLock)
        {
            if (closed ||
                activeOperation !== operation ||
                operation.cancellationSignal.isCancellationRequested ||
                !operation.terminalCleanupScheduled.compareAndSet(false, true))
            {
                return
            }
            speechEngine
        }
        operation.cancellationSignal.requestCancellation()
        scheduleCancellationWatchdog(operation)
        engineToCancel?.requestCancellation()
        nativeWorker.execute {
            drainFailedOperation(operation, DictationFailure.SPEECH_ENGINE_FAILURE, engineToCancel)
        }
    }

    private fun stopAndFinalize(operation: ActiveOperation)
    {
        if (!isCurrentOperation(operation))
        {
            return
        }

        try
        {
            speechEngine?.stopAndFlush()

            if (!isFinalizationAllowed(operation))
            {
                return
            }

            safelyNotify {
                operation.endpoint.onAudioCaptureFinished(
                    operation.request.operationContext.operationIdentifier
                )
            }

            val rawTranscript = transcriptAccumulator.snapshot().completedRawTranscript
            val polishingWatchdogs = if (operation.request.transcriptMode == TranscriptMode.POLISHED)
            {
                schedulePolishingWatchdogs(operation)
            }
            else
            {
                null
            }
            val processedTranscript = try
            {
                runBlocking {
                    transcriptProcessingPipeline.process(
                        operationContext = operation.request.operationContext,
                        exactRawTranscript = rawTranscript,
                        mode = operation.request.transcriptMode
                    )
                }
            }
            catch (cancellationException: kotlinx.coroutines.CancellationException)
            {
                if (!isFinalizationAllowed(operation))
                {
                    throw cancellationException
                }

                val cleanTranscript = runBlocking {
                    transcriptProcessingPipeline.process(
                        operationContext = operation.request.operationContext,
                        exactRawTranscript = rawTranscript,
                        mode = TranscriptMode.CLEAN
                    )
                }
                cleanTranscript.copy(
                    selectedMode = TranscriptMode.POLISHED,
                    usedDeterministicFallback = true,
                    fallbackReason = TranscriptFallbackReason.INFERENCE_TIMEOUT
                )
            }
            finally
            {
                polishingWatchdogs?.cancel()
            }

            if (!detachFinalizedIfCurrent(operation))
            {
                return
            }

            transcriptAccumulator.cancelSession(operation.accumulatorSessionIdentifier)
            safelyNotify {
                operation.endpoint.onFinalTranscript(
                    operation.request.operationContext.operationIdentifier,
                    processedTranscript
                )
            }
            releaseIdleTextModelOnNativeWorker()
        }
        catch (_: kotlinx.coroutines.CancellationException)
        {
            // The independently scheduled cancellation drain owns terminal notification.
        }
        catch (_: Exception)
        {
            drainFailedOperation(operation, DictationFailure.SPEECH_ENGINE_FAILURE)
        }
    }

    private fun drainFailedOperation(
        operation: ActiveOperation,
        failure: DictationFailure,
        selectedEngine: StreamingSpeechEngine? = synchronized(coordinationLock) { speechEngine }
    )
    {
        operation.terminalCleanupScheduled.set(true)
        operation.cancellationSignal.requestCancellation()
        scheduleCancellationWatchdog(operation)
        selectedEngine?.requestCancellation()

        try
        {
            selectedEngine?.cancelAndDrain()
        }
        catch (_: Throwable)
        {
            markSpeechEngineUnusable(selectedEngine)
        }

        if (!detachIfCurrent(operation))
        {
            return
        }

        transcriptAccumulator.cancelSession(operation.accumulatorSessionIdentifier)
        releaseIdleTextModelOnNativeWorker()
        safelyNotify {
            operation.endpoint.onFailure(operation.request.operationContext.operationIdentifier, failure)
        }
    }

    private fun drainCancelledOperation(
        operation: ActiveOperation,
        selectedEngine: StreamingSpeechEngine?,
        notifyClient: Boolean
    )
    {
        try
        {
            selectedEngine?.cancelAndDrain()
        }
        catch (_: Throwable)
        {
            markSpeechEngineUnusable(selectedEngine)
        }

        if (!detachIfCurrent(operation))
        {
            return
        }

        transcriptAccumulator.cancelSession(operation.accumulatorSessionIdentifier)
        releaseIdleTextModelOnNativeWorker()

        if (notifyClient)
        {
            safelyNotify {
                operation.endpoint.onOperationCancelled(operation.request.operationContext.operationIdentifier)
            }
        }
    }

    private fun markSpeechEngineUnusable(selectedEngine: StreamingSpeechEngine?)
    {
        val modelStateChanged = synchronized(coordinationLock)
        {
            if (speechEngine === selectedEngine)
            {
                nativeProcessPoisoned = true
                speechModelState = SpeechModelState.FAILED
                true
            }
            else
            {
                false
            }
        }

        if (modelStateChanged)
        {
            notifyModelState(SpeechModelState.FAILED)
            reportFatalNativeFailureOnce()
        }
    }

    private fun scheduleCancellationWatchdog(operation: ActiveOperation)
    {
        try
        {
            cancellationWatchdog.schedule(
                {
                    val operationStillUndrained = synchronized(coordinationLock)
                    {
                        !closed &&
                            activeOperation === operation &&
                            operation.cancellationSignal.isCancellationRequested
                    }

                    if (operationStillUndrained)
                    {
                        synchronized(coordinationLock)
                        {
                            nativeProcessPoisoned = true
                            speechModelState = SpeechModelState.FAILED
                        }
                        notifyModelState(SpeechModelState.FAILED)
                        reportFatalNativeFailureOnce()
                    }
                },
                cancellationWatchdogMilliseconds,
                TimeUnit.MILLISECONDS
            )
        }
        catch (_: RejectedExecutionException)
        {
            // Coordinator teardown already owns the operation and process lifetime.
        }
    }

    private fun reportFatalNativeFailureOnce()
    {
        if (fatalFailureReported.compareAndSet(false, true))
        {
            fatalNativeFailureHandler()
        }
    }

    private fun detachIfCurrent(operation: ActiveOperation): Boolean
    {
        return synchronized(coordinationLock)
        {
            if (activeOperation !== operation)
            {
                false
            }
            else
            {
                activeOperation = null
                true
            }
        }
    }

    /**
     * Atomically prevents cancellation from racing between the last finalization check and detach.
     */
    private fun detachFinalizedIfCurrent(operation: ActiveOperation): Boolean
    {
        return synchronized(coordinationLock)
        {
            if (activeOperation !== operation ||
                operation.cancellationSignal.isCancellationRequested ||
                operation.terminalCleanupScheduled.get())
            {
                false
            }
            else
            {
                activeOperation = null
                true
            }
        }
    }

    private fun isFinalizationAllowed(operation: ActiveOperation): Boolean
    {
        return synchronized(coordinationLock)
        {
            !closed &&
                activeOperation === operation &&
                !operation.cancellationSignal.isCancellationRequested &&
                !operation.terminalCleanupScheduled.get()
        }
    }

    private fun isCurrentOperation(operation: ActiveOperation): Boolean
    {
        return synchronized(coordinationLock)
        {
            !closed && activeOperation === operation && !operation.cancellationSignal.isCancellationRequested
        }
    }

    private fun findOwnedOperation(clientIdentifier: String, operationIdentifier: String): ActiveOperation?
    {
        return synchronized(coordinationLock)
        {
            activeOperation?.takeIf { operation ->
                operation.request.operationContext.clientSessionIdentifier.value == clientIdentifier &&
                    operation.request.operationContext.operationIdentifier.value == operationIdentifier
            }
        }
    }

    private fun determineMissingStopResult(clientIdentifier: String, operationIdentifier: String): StopDictationResult
    {
        return synchronized(coordinationLock)
        {
            val currentOperation = activeOperation

            if (currentOperation == null)
            {
                StopDictationResult.OPERATION_NOT_ACTIVE
            }
            else if (currentOperation.request.operationContext.clientSessionIdentifier.value != clientIdentifier ||
                currentOperation.request.operationContext.operationIdentifier.value != operationIdentifier)
            {
                StopDictationResult.CLIENT_DOES_NOT_OWN_OPERATION
            }
            else
            {
                StopDictationResult.OPERATION_NOT_ACTIVE
            }
        }
    }

    private fun cancelDetachedOperation(operation: ActiveOperation?, notifyClient: Boolean)
    {
        if (operation == null)
        {
            return
        }

        operation.cancellationSignal.requestCancellation()
        if (!operation.terminalCleanupScheduled.compareAndSet(false, true))
        {
            return
        }
        val engineToCancel = synchronized(coordinationLock)
        {
            speechEngine
        }
        scheduleCancellationWatchdog(operation)
        engineToCancel?.requestCancellation()
        requestTextPolishingCancellation(operation)

        nativeWorker.execute {
            drainCancelledOperation(operation, engineToCancel, notifyClient)
        }
    }

    private fun schedulePolishingWatchdogs(operation: ActiveOperation): PolishingWatchdogs?
    {
        return try
        {
            val cancellationFuture = cancellationWatchdog.schedule(
                {
                    if (isFinalizationAllowed(operation))
                    {
                        requestTextPolishingCancellation(operation)
                    }
                },
                polishingCancellationMilliseconds,
                TimeUnit.MILLISECONDS
            )
            val fatalFuture = cancellationWatchdog.schedule(
                {
                    val operationStillFinalizing = synchronized(coordinationLock)
                    {
                        !closed && activeOperation === operation
                    }

                    if (operationStillFinalizing)
                    {
                        synchronized(coordinationLock)
                        {
                            nativeProcessPoisoned = true
                            speechModelState = SpeechModelState.FAILED
                        }
                        notifyModelState(SpeechModelState.FAILED)
                        reportFatalNativeFailureOnce()
                    }
                },
                polishingFatalWatchdogMilliseconds,
                TimeUnit.MILLISECONDS
            )
            PolishingWatchdogs(cancellationFuture, fatalFuture)
        }
        catch (_: RejectedExecutionException)
        {
            null
        }
    }

    private fun releaseNativeResources()
    {
        val engine: StreamingSpeechEngine?
        val modelLease: VerifiedSpeechModelLease?

        synchronized(coordinationLock)
        {
            engine = speechEngine
            modelLease = verifiedModelLease
            speechEngine = null
            verifiedModelLease = null
        }

        try
        {
            engine?.cancelAndDrain()
            engine?.close()
        }
        finally
        {
            try
            {
                modelLease?.close()
            }
            finally
            {
                if (transcriptPolisher is AutoCloseable)
                {
                    transcriptPolisher.close()
                }
            }
        }
    }

    /**
     * Requests memory release without racing an active transcript operation.
     */
    fun releaseIdleTextModel()
    {
        if (isClosed())
        {
            return
        }

        try
        {
            nativeWorker.execute(::releaseIdleTextModelOnNativeWorker)
        }
        catch (_: RejectedExecutionException)
        {
            // Coordinator teardown already owns native lifetime.
        }
    }

    /**
     * Releases both native models under operating-system memory pressure when no operation owns them.
     */
    fun releaseIdleModelsForMemoryPressure()
    {
        if (isClosed())
        {
            return
        }

        try
        {
            nativeWorker.execute {
                val resources = synchronized(coordinationLock)
                {
                    if (closed || activeOperation != null)
                    {
                        null
                    }
                    else
                    {
                        val detachedResources = Pair(speechEngine, verifiedModelLease)
                        speechEngine = null
                        verifiedModelLease = null
                        speechModelState = SpeechModelState.NOT_PREPARED
                        detachedResources
                    }
                } ?: return@execute

                try
                {
                    try
                    {
                        resources.first?.cancelAndDrain()
                        resources.first?.close()
                    }
                    finally
                    {
                        resources.second?.close()
                    }

                    val textReleaseResult = releaseIdleTextModelOnNativeWorker()

                    if (textReleaseResult != IdleTextModelReleaseResult.FATAL_FAILURE)
                    {
                        notifyModelState(SpeechModelState.NOT_PREPARED)
                    }
                }
                catch (_: Throwable)
                {
                    synchronized(coordinationLock)
                    {
                        nativeProcessPoisoned = true
                        speechModelState = SpeechModelState.FAILED
                    }
                    notifyModelState(SpeechModelState.FAILED)
                    reportFatalNativeFailureOnce()
                }
            }
        }
        catch (_: RejectedExecutionException)
        {
            // Coordinator teardown already owns native lifetime.
        }
    }

    private fun releaseIdleTextModelOnNativeWorker(): IdleTextModelReleaseResult?
    {
        val releaseResult =
            (transcriptPolisher as? IdleReleasableTranscriptPolisher)?.releaseModelIfIdle()

        if (releaseResult == IdleTextModelReleaseResult.FATAL_FAILURE)
        {
            synchronized(coordinationLock)
            {
                nativeProcessPoisoned = true
                speechModelState = SpeechModelState.FAILED
            }
            notifyModelState(SpeechModelState.FAILED)
            reportFatalNativeFailureOnce()
        }
        return releaseResult
    }

    /**
     * Runs outside the serialized native worker so generation can be interrupted while it owns that worker.
     */
    private fun requestTextPolishingCancellation(operation: ActiveOperation)
    {
        try
        {
            textCancellationWorker.execute {
                try
                {
                    runBlocking {
                        transcriptPolisher.cancel(operation.request.operationContext.operationIdentifier)
                    }
                }
                catch (_: Throwable)
                {
                    // The cancellation watchdog contains an unresponsive or failed native text engine.
                }
            }
        }
        catch (_: RejectedExecutionException)
        {
            // Coordinator teardown already owns native lifetime.
        }
    }

    private fun isClosed(): Boolean
    {
        return synchronized(coordinationLock)
        {
            closed
        }
    }

    private fun notifyModelState(state: SpeechModelState)
    {
        val endpointSnapshot = synchronized(coordinationLock)
        {
            clients.values.toList()
        }

        endpointSnapshot.forEach { endpoint ->
            safelyNotify {
                endpoint.onSpeechModelStateChanged(state)
            }
        }
    }

    private fun safelyNotify(callback: () -> Unit)
    {
        try
        {
            callback()
        }
        catch (_: Exception)
        {
            // A dead or faulty client must never terminate the inference coordinator.
        }
    }

    private data class ActiveOperation(
        val request: BeginDictationRequest,
        val endpoint: InferenceClientEndpoint,
        val cancellationSignal: InferenceCancellationSignal,
        val accumulatorSessionIdentifier: Long,
        val terminalCleanupScheduled: AtomicBoolean = AtomicBoolean(false)
    )

    private data class PolishingWatchdogs(
        val cancellationFuture: ScheduledFuture<*>,
        val fatalFuture: ScheduledFuture<*>
    )
    {
        fun cancel()
        {
            cancellationFuture.cancel(false)
            fatalFuture.cancel(false)
        }
    }

    private companion object
    {
        const val CLOSE_TIMEOUT_SECONDS = 3L
    }
}

/**
 * Makes Polished mode fail closed to deterministic Clean text until Android llama.cpp is attached.
 */
private object UnavailableAndroidTranscriptPolisher : TranscriptPolisher
{
    override suspend fun polish(
        operationContext: InferenceOperationContext,
        request: TranscriptPolishingRequest
    ): String
    {
        throw LocalInferenceException(
            InferenceFailureCategory.MODEL_NOT_READY,
            diagnosticCode = "ANDROID_TEXT_MODEL_NOT_READY"
        )
    }

    override suspend fun cancel(operationIdentifier: OperationIdentifier): CancellationAcknowledgement
    {
        return CancellationAcknowledgement(operationIdentifier)
    }
}
