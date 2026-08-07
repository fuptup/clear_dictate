package com.cleardictate.desktop

import com.cleardictate.desktop.inference.WindowsTextWorkerClient
import com.cleardictate.desktop.inference.WindowsTextWorkerConfiguration
import com.cleardictate.domain.ProcessedTranscript
import com.cleardictate.domain.TranscriptFallbackReason
import com.cleardictate.domain.TranscriptMode
import com.cleardictate.domain.TranscriptPolisher
import com.cleardictate.domain.TranscriptPolishingRequest
import com.cleardictate.domain.TranscriptProcessingPipeline
import com.cleardictate.inference.CancellationAcknowledgement
import com.cleardictate.inference.ClientSessionIdentifier
import com.cleardictate.inference.InferenceOperationContext
import com.cleardictate.inference.OperationIdentifier
import com.cleardictate.inference.OperationPrivacy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * Extends the transcript-polisher contract with deterministic resource ownership.
 */
interface DesktopTextWorker : TranscriptPolisher, AutoCloseable

/**
 * Starts the isolated Windows worker only when Polished mode first needs it.
 */
fun interface DesktopTextWorkerFactory
{
    suspend fun start(configuration: DesktopRuntimeConfiguration): DesktopTextWorker
}

/**
 * Owns one serialized developer-preview transcript pipeline and its lazily loaded model worker.
 */
class DesktopTranscriptProcessor(
    private val runtimeConfiguration: DesktopRuntimeConfiguration?,
    private val workerFactory: DesktopTextWorkerFactory = WindowsDesktopTextWorkerFactory()
) : DesktopTranscriptRewriter
{
    private val operationMutex = Mutex()
    private val ownershipLock = Any()
    private val operationSequence = AtomicLong(0)
    private val clientSessionIdentifier = ClientSessionIdentifier(
        "desktop_" + UUID.randomUUID().toString().replace("-", "")
    )

    @Volatile
    private var activeWorker: DesktopTextWorker? = null

    @Volatile
    private var closed = false

    suspend fun process(exactRawTranscript: String, mode: TranscriptMode): ProcessedTranscript
    {
        return operationMutex.withLock {
            ensureOpen()
            val polisher = if (mode == TranscriptMode.POLISHED)
            {
                requireNotNull(runtimeConfiguration) { "Polished mode is unavailable until the local worker and model are configured." }
                LazyDesktopTextWorker()
            }
            else
            {
                UnavailablePolisher
            }

            val operationIdentifier = OperationIdentifier("desktop_${operationSequence.incrementAndGet()}")
            val processedTranscript = withContext(Dispatchers.Default)
            {
                TranscriptProcessingPipeline(polisher = polisher).process(
                    operationContext = InferenceOperationContext(
                        clientSessionIdentifier = clientSessionIdentifier,
                        operationIdentifier = operationIdentifier,
                        privacy = OperationPrivacy.PRIVATE
                    ),
                    exactRawTranscript = exactRawTranscript,
                    mode = mode
                )
            }

            if (processedTranscript.fallbackReason == TranscriptFallbackReason.INFERENCE_FAILURE ||
                processedTranscript.fallbackReason == TranscriptFallbackReason.CANCELLATION_NOT_ACKNOWLEDGED)
            {
                discardActiveWorker()
            }

            processedTranscript
        }
    }

    /**
     * Runs the complete deterministic-cleaning and local-polishing path selected by push-to-talk.
     */
    override suspend fun rewrite(rawTranscript: String): String
    {
        return process(rawTranscript, TranscriptMode.POLISHED).selectedTranscript
    }

    suspend fun restartWorker()
    {
        operationMutex.withLock {
            ensureOpen()
            discardActiveWorker()
        }
    }

    override fun close()
    {
        val workerToClose = synchronized(ownershipLock)
        {
            if (closed)
            {
                return
            }

            closed = true
            activeWorker.also { activeWorker = null }
        }
        workerToClose?.close()
    }

    private suspend fun acquireWorker(): DesktopTextWorker
    {
        synchronized(ownershipLock)
        {
            ensureOpen()
            activeWorker?.let { return it }
        }

        val configuration = requireNotNull(runtimeConfiguration)
        val startedWorker = workerFactory.start(configuration)

        try
        {
            currentCoroutineContext().ensureActive()
            return synchronized(ownershipLock)
            {
                ensureOpen()
                activeWorker ?: startedWorker.also { activeWorker = it }
            }
        }
        catch (throwable: Throwable)
        {
            startedWorker.close()
            throw throwable
        }
    }

    private fun discardActiveWorker()
    {
        val workerToClose = synchronized(ownershipLock)
        {
            activeWorker.also { activeWorker = null }
        }
        workerToClose?.close()
    }

    private fun ensureOpen()
    {
        check(!closed) { "The desktop transcript processor is closed." }
    }

    /**
     * Defers worker startup until the pipeline has completed deterministic cleaning,
     * empty-input rejection, and prompt-budget preflight.
     */
    private inner class LazyDesktopTextWorker : TranscriptPolisher
    {
        override suspend fun polish(operationContext: InferenceOperationContext, request: TranscriptPolishingRequest): String
        {
            return acquireWorker().polish(operationContext, request)
        }

        override suspend fun cancel(operationIdentifier: OperationIdentifier): CancellationAcknowledgement
        {
            val worker = synchronized(ownershipLock) { activeWorker }
            return worker?.cancel(operationIdentifier) ?: CancellationAcknowledgement(operationIdentifier)
        }
    }

    private object UnavailablePolisher : TranscriptPolisher
    {
        override suspend fun polish(operationContext: InferenceOperationContext, request: TranscriptPolishingRequest): String
        {
            error("Raw and Clean processing must not invoke the local model.")
        }

        override suspend fun cancel(operationIdentifier: OperationIdentifier): CancellationAcknowledgement
        {
            error("Raw and Clean processing must not request model cancellation.")
        }
    }
}

/**
 * Adapts the production Windows process client to the desktop application's ownership boundary.
 */
private class WindowsDesktopTextWorkerFactory : DesktopTextWorkerFactory
{
    override suspend fun start(configuration: DesktopRuntimeConfiguration): DesktopTextWorker
    {
        val client = WindowsTextWorkerClient.start(
            WindowsTextWorkerConfiguration(
                workerExecutable = configuration.textWorkerExecutable,
                workerLauncherExecutable = configuration.workerLauncherExecutable,
                modelPath = configuration.textModelPath,
                inferenceThreadCount = configuration.inferenceThreadCount
            )
        )

        return WindowsDesktopTextWorker(client)
    }
}

private class WindowsDesktopTextWorker(
    private val client: WindowsTextWorkerClient
) : DesktopTextWorker, TranscriptPolisher by client
{
    override fun close()
    {
        client.close()
    }
}
