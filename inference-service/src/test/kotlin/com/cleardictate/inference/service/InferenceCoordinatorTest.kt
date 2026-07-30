package com.cleardictate.inference.service

import com.cleardictate.domain.TranscriptMode
import com.cleardictate.domain.ProcessedTranscript
import com.cleardictate.domain.TranscriptPolisher
import com.cleardictate.domain.TranscriptPolishingRequest
import com.cleardictate.inference.CancellationAcknowledgement
import com.cleardictate.inference.ClientSessionIdentifier
import com.cleardictate.inference.InferenceOperationContext
import com.cleardictate.inference.OperationIdentifier
import com.cleardictate.inference.OperationPrivacy
import kotlinx.coroutines.CancellationException
import org.junit.After
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Specifies single ownership, callback isolation, cancellation, and resource ordering.
 */
class InferenceCoordinatorTest
{
    private val modelProvider = RecordingSpeechModelProvider()
    private val engineFactory = RecordingSpeechEngineFactory(modelProvider.lifecycleEvents)
    private val coordinator = InferenceCoordinator(modelProvider, engineFactory)

    @After
    fun closeCoordinator()
    {
        coordinator.close()
    }

    @Test
    fun `only one client can own an active dictation`()
    {
        prepareSpeechEngine()
        val firstClient = RecordingClientEndpoint()
        val secondClient = RecordingClientEndpoint()
        coordinator.registerClient(clientIdentifier("client-one"), firstClient)
        coordinator.registerClient(clientIdentifier("client-two"), secondClient)

        assertEquals(
            BeginDictationResult.ACCEPTED,
            coordinator.beginDictation(request("client-one", "operation-one"))
        )
        assertEquals(
            BeginDictationResult.BUSY,
            coordinator.beginDictation(request("client-two", "operation-two"))
        )

        assertEquals(listOf("operation-one"), firstClient.acceptedOperations)
        assertEquals(listOf("operation-two"), secondClient.busyOperations)
    }

    @Test
    fun `recognition callbacks are delivered only to the operation owner`()
    {
        prepareSpeechEngine()
        val firstClient = RecordingClientEndpoint()
        val secondClient = RecordingClientEndpoint()
        coordinator.registerClient(clientIdentifier("client-one"), firstClient)
        coordinator.registerClient(clientIdentifier("client-two"), secondClient)
        coordinator.beginDictation(request("client-one", "operation-one"))

        val engine = engineFactory.awaitStartedEngine()
        engine.listener.onPartial(lineIdentifier = 7L, text = "Owner only")

        assertEquals(listOf("Owner only"), firstClient.partialTranscripts)
        assertTrue(secondClient.partialTranscripts.isEmpty())
    }

    @Test
    fun `late callback from cancelled operation cannot contaminate a replacement operation`()
    {
        prepareSpeechEngine()
        val client = RecordingClientEndpoint()
        coordinator.registerClient(clientIdentifier("client-one"), client)
        coordinator.beginDictation(request("client-one", "operation-one"))
        val engine = engineFactory.awaitStartedEngine()
        val staleListener = engine.listener

        assertEquals(CancelDictationResult.CANCELLATION_ACCEPTED, coordinator.cancel("client-one", "operation-one"))
        assertTrue(client.cancellationReported.await(2, TimeUnit.SECONDS))
        assertEquals(BeginDictationResult.ACCEPTED, coordinator.beginDictation(request("client-one", "operation-two")))

        staleListener.onPartial(lineIdentifier = 8L, text = "Stale private words")
        staleListener.onFailure()

        assertFalse(client.partialTranscripts.contains("Stale private words"))
        assertEquals(listOf("operation-one"), client.cancelledOperations)
        assertTrue(client.failures.isEmpty())
        assertTrue(coordinator.hasActiveOperation())
    }

    @Test
    fun `unregistering an owning client cancels its operation immediately`()
    {
        prepareSpeechEngine()
        val client = RecordingClientEndpoint()
        coordinator.registerClient(clientIdentifier("client-one"), client)
        coordinator.beginDictation(request("client-one", "operation-one"))
        val engine = engineFactory.awaitStartedEngine()

        coordinator.unregisterClient(clientIdentifier("client-one"))

        assertTrue(engine.cancellationRequested)
        val cancellationDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)

        while (coordinator.hasActiveOperation() && System.nanoTime() < cancellationDeadline)
        {
            Thread.yield()
        }

        assertFalse(coordinator.hasActiveOperation())
    }

    @Test
    fun `cancellation remains responsive while native session start is blocked`()
    {
        engineFactory.blockEngineStart = true
        val engine = prepareSpeechEngine()
        val client = RecordingClientEndpoint()
        coordinator.registerClient(clientIdentifier("client-one"), client)
        coordinator.beginDictation(request("client-one", "operation-one"))
        assertTrue(engine.startEntered.await(2, TimeUnit.SECONDS))

        val startNanoseconds = System.nanoTime()
        val cancellationResult = coordinator.cancel("client-one", "operation-one")
        val elapsedMilliseconds = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanoseconds)

        assertEquals(CancelDictationResult.CANCELLATION_ACCEPTED, cancellationResult)
        assertTrue(elapsedMilliseconds < 100, "Cancellation took $elapsedMilliseconds ms.")
        assertTrue(client.cancelledOperations.isEmpty())
        assertTrue(engine.cancellationRequested)
        engine.allowStart.countDown()
        assertTrue(client.cancellationReported.await(2, TimeUnit.SECONDS))
    }

    @Test
    fun `undrained cancellation poisons the private inference process`()
    {
        val isolatedModelProvider = RecordingSpeechModelProvider()
        val isolatedEngineFactory = RecordingSpeechEngineFactory(isolatedModelProvider.lifecycleEvents).apply {
            blockEngineStart = true
        }
        val fatalFailureReported = CountDownLatch(1)
        val isolatedCoordinator = InferenceCoordinator(
            verifiedSpeechModelProvider = isolatedModelProvider,
            streamingSpeechEngineFactory = isolatedEngineFactory,
            fatalNativeFailureHandler = fatalFailureReported::countDown,
            cancellationWatchdogMilliseconds = 25L
        )

        try
        {
            val client = RecordingClientEndpoint()
            isolatedCoordinator.registerClient(clientIdentifier("client-one"), client)
            isolatedCoordinator.prepareSpeechModel()
            val engine = isolatedEngineFactory.awaitOpenedEngine()
            val readinessDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)

            while (isolatedCoordinator.currentSpeechModelState() != SpeechModelState.READY &&
                System.nanoTime() < readinessDeadline)
            {
                Thread.yield()
            }

            assertEquals(
                BeginDictationResult.ACCEPTED,
                isolatedCoordinator.beginDictation(request("client-one", "operation-one"))
            )
            assertTrue(engine.startEntered.await(2, TimeUnit.SECONDS))
            assertEquals(
                CancelDictationResult.CANCELLATION_ACCEPTED,
                isolatedCoordinator.cancel("client-one", "operation-one")
            )
            assertTrue(fatalFailureReported.await(2, TimeUnit.SECONDS))
            assertEquals(SpeechModelState.FAILED, isolatedCoordinator.currentSpeechModelState())
            engine.allowStart.countDown()
        }
        finally
        {
            isolatedCoordinator.close()
        }
    }

    @Test
    fun `replacement remains busy until cancelled native session is drained`()
    {
        val engine = prepareSpeechEngine()
        val client = RecordingClientEndpoint()
        coordinator.registerClient(clientIdentifier("client-one"), client)
        coordinator.beginDictation(request("client-one", "operation-one"))
        engineFactory.awaitStartedEngine()
        engine.blockCancelDrain = true

        assertEquals(CancelDictationResult.CANCELLATION_ACCEPTED, coordinator.cancel("client-one", "operation-one"))
        assertTrue(engine.cancelDrainEntered.await(2, TimeUnit.SECONDS))
        assertEquals(
            BeginDictationResult.BUSY,
            coordinator.beginDictation(request("client-one", "operation-two"))
        )

        engine.allowCancelDrain.countDown()
        assertTrue(client.cancellationReported.await(2, TimeUnit.SECONDS))
        assertEquals(
            BeginDictationResult.ACCEPTED,
            coordinator.beginDictation(request("client-one", "operation-two"))
        )
    }

    @Test
    fun `cancellation during blocked finalization suppresses the final transcript`()
    {
        val engine = prepareSpeechEngine()
        val client = RecordingClientEndpoint()
        coordinator.registerClient(clientIdentifier("client-one"), client)
        coordinator.beginDictation(request("client-one", "operation-one"))
        engineFactory.awaitStartedEngine()
        engine.completedTextOnStop = "This text must not be inserted."
        engine.blockStopFlush = true

        assertEquals(StopDictationResult.STOP_ACCEPTED, coordinator.stop("client-one", "operation-one"))
        assertTrue(engine.stopFlushEntered.await(2, TimeUnit.SECONDS))
        assertEquals(CancelDictationResult.CANCELLATION_ACCEPTED, coordinator.cancel("client-one", "operation-one"))

        engine.allowStopFlush.countDown()

        assertTrue(client.cancellationReported.await(2, TimeUnit.SECONDS))
        assertFalse(client.finalTranscriptReceived.await(100, TimeUnit.MILLISECONDS))
        assertEquals(null, client.finalTranscript)
    }

    @Test
    fun `cancellation reaches active text polishing without waiting behind native worker`()
    {
        val isolatedModelProvider = RecordingSpeechModelProvider()
        val isolatedEngineFactory = RecordingSpeechEngineFactory(isolatedModelProvider.lifecycleEvents)
        val blockingPolisher = BlockingTranscriptPolisher()
        val isolatedCoordinator = InferenceCoordinator(
            verifiedSpeechModelProvider = isolatedModelProvider,
            streamingSpeechEngineFactory = isolatedEngineFactory,
            transcriptPolisher = blockingPolisher
        )

        try
        {
            val client = RecordingClientEndpoint()
            isolatedCoordinator.registerClient(clientIdentifier("client-one"), client)
            isolatedCoordinator.prepareSpeechModel()
            val engine = isolatedEngineFactory.awaitOpenedEngine()
            val readinessDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)

            while (isolatedCoordinator.currentSpeechModelState() != SpeechModelState.READY &&
                System.nanoTime() < readinessDeadline)
            {
                Thread.yield()
            }

            isolatedCoordinator.beginDictation(
                request("client-one", "operation-polishing").copy(transcriptMode = TranscriptMode.POLISHED)
            )
            assertTrue(engine.started.await(2, TimeUnit.SECONDS))
            engine.completedTextOnStop = "Please polish this."
            assertEquals(
                StopDictationResult.STOP_ACCEPTED,
                isolatedCoordinator.stop("client-one", "operation-polishing")
            )
            assertTrue(blockingPolisher.polishingStarted.await(2, TimeUnit.SECONDS))

            assertEquals(
                CancelDictationResult.CANCELLATION_ACCEPTED,
                isolatedCoordinator.cancel("client-one", "operation-polishing")
            )

            assertTrue(blockingPolisher.cancellationReceived.await(2, TimeUnit.SECONDS))
            assertTrue(client.cancellationReported.await(2, TimeUnit.SECONDS))
            assertFalse(client.finalTranscriptReceived.await(100, TimeUnit.MILLISECONDS))
            assertTrue(client.failures.isEmpty())
        }
        finally
        {
            isolatedCoordinator.close()
        }
    }

    @Test
    fun `wall clock polishing timeout requests native cancellation and returns Clean fallback`()
    {
        val isolatedModelProvider = RecordingSpeechModelProvider()
        val isolatedEngineFactory = RecordingSpeechEngineFactory(isolatedModelProvider.lifecycleEvents)
        val blockingPolisher = BlockingTranscriptPolisher()
        val isolatedCoordinator = InferenceCoordinator(
            verifiedSpeechModelProvider = isolatedModelProvider,
            streamingSpeechEngineFactory = isolatedEngineFactory,
            transcriptPolisher = blockingPolisher,
            polishingCancellationMilliseconds = 25L,
            polishingFatalWatchdogMilliseconds = 500L
        )

        try
        {
            val client = RecordingClientEndpoint()
            isolatedCoordinator.registerClient(clientIdentifier("client-one"), client)
            isolatedCoordinator.prepareSpeechModel()
            val engine = isolatedEngineFactory.awaitOpenedEngine()
            val readinessDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)

            while (isolatedCoordinator.currentSpeechModelState() != SpeechModelState.READY &&
                System.nanoTime() < readinessDeadline)
            {
                Thread.yield()
            }

            isolatedCoordinator.beginDictation(
                request("client-one", "operation-timeout").copy(transcriptMode = TranscriptMode.POLISHED)
            )
            assertTrue(engine.started.await(2, TimeUnit.SECONDS))
            engine.completedTextOnStop = "Um, preserve this exact value 42."
            assertEquals(
                StopDictationResult.STOP_ACCEPTED,
                isolatedCoordinator.stop("client-one", "operation-timeout")
            )

            assertTrue(blockingPolisher.cancellationReceived.await(2, TimeUnit.SECONDS))
            assertTrue(client.finalTranscriptReceived.await(2, TimeUnit.SECONDS))
            val result = assertNotNull(client.finalTranscript)
            assertTrue(result.usedDeterministicFallback)
            assertEquals(com.cleardictate.domain.TranscriptFallbackReason.INFERENCE_TIMEOUT, result.fallbackReason)
            assertEquals(TranscriptMode.POLISHED, result.selectedMode)
            assertTrue(client.failures.isEmpty())
        }
        finally
        {
            isolatedCoordinator.close()
        }
    }

    @Test
    fun `model verification completes before the native engine is opened`()
    {
        val client = RecordingClientEndpoint()
        coordinator.registerClient(clientIdentifier("client-one"), client)
        prepareSpeechEngine()

        assertEquals(listOf("verify-model", "open-native-engine"), modelProvider.lifecycleEvents)
    }

    @Test
    fun `closing coordinator releases native engine before verified model lease`()
    {
        val client = RecordingClientEndpoint()
        coordinator.registerClient(clientIdentifier("client-one"), client)
        prepareSpeechEngine()

        coordinator.close()

        assertEquals(
            listOf("verify-model", "open-native-engine", "close-native-engine", "close-model-lease"),
            modelProvider.lifecycleEvents
        )
    }

    @Test
    fun `memory pressure releases idle speech model and permits verified reload`()
    {
        val client = RecordingClientEndpoint()
        coordinator.registerClient(clientIdentifier("client-one"), client)
        prepareSpeechEngine()

        coordinator.releaseIdleModelsForMemoryPressure()
        val releaseDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)

        while (coordinator.currentSpeechModelState() != SpeechModelState.NOT_PREPARED &&
            System.nanoTime() < releaseDeadline)
        {
            Thread.yield()
        }

        assertEquals(SpeechModelState.NOT_PREPARED, coordinator.currentSpeechModelState())
        assertEquals(
            listOf("verify-model", "open-native-engine", "close-native-engine", "close-model-lease"),
            modelProvider.lifecycleEvents
        )
    }

    @Test
    fun `finalized Clean mode runs deterministic processing inside the service owner`()
    {
        prepareSpeechEngine()
        val client = RecordingClientEndpoint()
        coordinator.registerClient(clientIdentifier("client-one"), client)
        coordinator.beginDictation(
            request("client-one", "operation-one").copy(transcriptMode = TranscriptMode.CLEAN)
        )
        val engine = engineFactory.awaitStartedEngine()
        engine.completedTextOnStop = "Um, I think, uh, we should release it on Friday."

        assertEquals(
            StopDictationResult.STOP_ACCEPTED,
            coordinator.stop("client-one", "operation-one")
        )
        assertTrue(client.finalTranscriptReceived.await(2, TimeUnit.SECONDS))

        val processedTranscript = assertNotNull(client.finalTranscript)
        assertEquals(
            "Um, I think, uh, we should release it on Friday.",
            processedTranscript.exactRawTranscript
        )
        assertEquals("I think we should release it on Friday.", processedTranscript.selectedTranscript)
        assertEquals(TranscriptMode.CLEAN, processedTranscript.selectedMode)
    }

    @Test
    fun `speech engine failure drains the failed session before a replacement starts`()
    {
        prepareSpeechEngine()
        val client = RecordingClientEndpoint()
        coordinator.registerClient(clientIdentifier("client-one"), client)
        coordinator.beginDictation(request("client-one", "operation-one"))
        val engine = engineFactory.awaitStartedEngine()

        engine.listener.onFailure()

        assertTrue(client.failureReported.await(2, TimeUnit.SECONDS))
        assertEquals(listOf(DictationFailure.SPEECH_ENGINE_FAILURE), client.failures)
        assertEquals(
            BeginDictationResult.ACCEPTED,
            coordinator.beginDictation(request("client-one", "operation-two"))
        )
    }

    private fun request(clientIdentifier: String, operationIdentifier: String): BeginDictationRequest
    {
        return BeginDictationRequest(
            operationContext = InferenceOperationContext(
                clientSessionIdentifier = clientIdentifier(clientIdentifier),
                operationIdentifier = OperationIdentifier(operationIdentifier),
                privacy = OperationPrivacy.STANDARD
            ),
            transcriptMode = TranscriptMode.CLEAN
        )
    }

    private fun prepareSpeechEngine(): RecordingSpeechEngine
    {
        coordinator.prepareSpeechModel()
        val engine = engineFactory.awaitOpenedEngine()
        val readinessDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)

        while (coordinator.currentSpeechModelState() != SpeechModelState.READY &&
            System.nanoTime() < readinessDeadline)
        {
            Thread.yield()
        }

        assertEquals(SpeechModelState.READY, coordinator.currentSpeechModelState())
        return engine
    }

    private fun clientIdentifier(value: String): ClientSessionIdentifier
    {
        return ClientSessionIdentifier(value)
    }
}

private class BlockingTranscriptPolisher : TranscriptPolisher
{
    val polishingStarted = CountDownLatch(1)
    val cancellationReceived = CountDownLatch(1)
    private val allowPolishingToFinish = CountDownLatch(1)
    @Volatile
    private var cancellationRequested = false

    override suspend fun polish(
        operationContext: InferenceOperationContext,
        request: TranscriptPolishingRequest
    ): String
    {
        polishingStarted.countDown()
        allowPolishingToFinish.await(2, TimeUnit.SECONDS)

        if (cancellationRequested)
        {
            throw CancellationException("Test polishing was cancelled.")
        }
        return "Polished text."
    }

    override suspend fun cancel(operationIdentifier: OperationIdentifier): CancellationAcknowledgement
    {
        cancellationRequested = true
        cancellationReceived.countDown()
        allowPolishingToFinish.countDown()
        return CancellationAcknowledgement(operationIdentifier)
    }
}

private class RecordingClientEndpoint : InferenceClientEndpoint
{
    val acceptedOperations = mutableListOf<String>()
    val busyOperations = mutableListOf<String>()
    val partialTranscripts = mutableListOf<String>()
    val cancelledOperations = mutableListOf<String>()
    val finalTranscriptReceived = CountDownLatch(1)
    val failureReported = CountDownLatch(1)
    val cancellationReported = CountDownLatch(1)
    val failures = mutableListOf<DictationFailure>()
    var finalTranscript: ProcessedTranscript? = null

    override fun onOperationAccepted(operationIdentifier: OperationIdentifier)
    {
        acceptedOperations += operationIdentifier.value
    }

    override fun onOperationBusy(operationIdentifier: OperationIdentifier)
    {
        busyOperations += operationIdentifier.value
    }

    override fun onPartialTranscript(operationIdentifier: OperationIdentifier, rawPartialTranscript: String)
    {
        partialTranscripts += rawPartialTranscript
    }

    override fun onOperationCancelled(operationIdentifier: OperationIdentifier)
    {
        cancelledOperations += operationIdentifier.value
        cancellationReported.countDown()
    }

    override fun onFinalTranscript(operationIdentifier: OperationIdentifier, processedTranscript: ProcessedTranscript)
    {
        finalTranscript = processedTranscript
        finalTranscriptReceived.countDown()
    }

    override fun onFailure(operationIdentifier: OperationIdentifier, failure: DictationFailure)
    {
        failures += failure
        failureReported.countDown()
    }
}

private class RecordingSpeechModelProvider : VerifiedSpeechModelProvider
{
    val lifecycleEvents = mutableListOf<String>()

    override fun acquireVerifiedModel(cancellationSignal: InferenceCancellationSignal): VerifiedSpeechModelLease
    {
        lifecycleEvents += "verify-model"
        return object : VerifiedSpeechModelLease
        {
            override val verifiedModelDirectoryPath = "verified-model"

            override fun close()
            {
                lifecycleEvents += "close-model-lease"
            }
        }
    }
}

private class RecordingSpeechEngineFactory(
    private val lifecycleEvents: MutableList<String>
) : StreamingSpeechEngineFactory
{
    private val engineCreated = CountDownLatch(1)
    private var createdEngine: RecordingSpeechEngine? = null
    var blockEngineStart = false

    override fun open(verifiedModelLease: VerifiedSpeechModelLease): StreamingSpeechEngine
    {
        assertEquals("verified-model", verifiedModelLease.verifiedModelDirectoryPath)
        lifecycleEvents += "open-native-engine"
        createdEngine = RecordingSpeechEngine(
            blockStart = blockEngineStart,
            onClose = {
                lifecycleEvents += "close-native-engine"
            }
        )
        engineCreated.countDown()
        return assertNotNull(createdEngine)
    }

    fun awaitOpenedEngine(): RecordingSpeechEngine
    {
        assertTrue(engineCreated.await(2, TimeUnit.SECONDS))
        return assertNotNull(createdEngine)
    }

    fun awaitStartedEngine(): RecordingSpeechEngine
    {
        return awaitOpenedEngine().also { engine ->
            assertTrue(engine.started.await(2, TimeUnit.SECONDS))
        }
    }
}

private class RecordingSpeechEngine(
    private val blockStart: Boolean,
    private val onClose: () -> Unit
) : StreamingSpeechEngine
{
    val started = CountDownLatch(1)
    val startEntered = CountDownLatch(1)
    val allowStart = CountDownLatch(1)
    val cancelDrainEntered = CountDownLatch(1)
    val allowCancelDrain = CountDownLatch(1)
    val stopFlushEntered = CountDownLatch(1)
    val allowStopFlush = CountDownLatch(1)
    lateinit var listener: StreamingSpeechEventListener
    var cancellationRequested = false
    var completedTextOnStop: String? = null
    var blockCancelDrain = false
    var blockStopFlush = false

    override fun start(listener: StreamingSpeechEventListener)
    {
        this.listener = listener
        startEntered.countDown()

        if (blockStart)
        {
            allowStart.await(2, TimeUnit.SECONDS)
        }

        started.countDown()
    }

    override fun stopAndFlush()
    {
        stopFlushEntered.countDown()

        if (blockStopFlush)
        {
            allowStopFlush.await(2, TimeUnit.SECONDS)
        }

        completedTextOnStop?.let { completedText ->
            listener.onCompleted(lineIdentifier = 99L, text = completedText)
        }
    }

    override fun requestCancellation()
    {
        cancellationRequested = true
    }

    override fun cancelAndDrain()
    {
        cancelDrainEntered.countDown()

        if (blockCancelDrain)
        {
            allowCancelDrain.await(2, TimeUnit.SECONDS)
        }
    }

    override fun close()
    {
        onClose()
    }
}
