package com.cleardictate.inference.service

import com.cleardictate.domain.TranscriptPolishingConfiguration
import com.cleardictate.domain.TranscriptPolishingRequest
import com.cleardictate.inference.ClientSessionIdentifier
import com.cleardictate.inference.InferenceOperationContext
import com.cleardictate.inference.OperationIdentifier
import com.cleardictate.inference.OperationPrivacy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Specifies deterministic loading, prompt isolation, cancellation, and resource lifetime.
 */
class AndroidQwenTranscriptPolisherTest
{
    @Test
    fun `verified model loads once and streamed tokens form the polished transcript`()
    {
        runBlocking {
            val lifecycleEvents = mutableListOf<String>()
            val modelProvider = RecordingTextModelProvider(lifecycleEvents)
            lateinit var inferenceEngine: RecordingTextInferenceEngine
            val polisher = AndroidQwenTranscriptPolisher(
                verifiedTextModelProvider = modelProvider,
                textInferenceEngineFactory = LocalTextInferenceEngineFactory {
                    RecordingTextInferenceEngine(
                        lifecycleEvents = lifecycleEvents,
                        generatedTokens = listOf("Clear", " text", ".")
                    ).also { createdEngine ->
                        inferenceEngine = createdEngine
                    }
                }
            )
            val request = request()

            val firstResult = polisher.polish(operationContext("operation-one"), request)
            val secondResult = polisher.polish(operationContext("operation-two"), request)

            assertEquals("Clear text.", firstResult)
            assertEquals("Clear text.", secondResult)
            assertEquals(1, modelProvider.acquisitionCount)
            assertEquals(listOf("verified-qwen.gguf"), inferenceEngine.loadedModelPaths)
            assertEquals(listOf(request.configuration), inferenceEngine.loadedConfigurations)
            assertEquals(listOf(request.systemInstruction, request.systemInstruction), inferenceEngine.systemPrompts)
            assertEquals(listOf(request.userMessage, request.userMessage), inferenceEngine.userPrompts)

            polisher.close()
            assertEquals(
                listOf("lease-acquired", "engine-created", "engine-closed", "lease-closed"),
                lifecycleEvents
            )
        }
    }

    @Test
    fun `cancellation acknowledgement waits until matching generation has drained`()
    {
        runBlocking {
            val generationStarted = CompletableDeferred<Unit>()
            val generationMayFinish = CompletableDeferred<Unit>()
            val inferenceEngine = RecordingTextInferenceEngine(
                lifecycleEvents = mutableListOf(),
                generatedTokens = emptyList(),
                generationStarted = generationStarted,
                generationMayFinish = generationMayFinish
            )
            val polisher = AndroidQwenTranscriptPolisher(
                verifiedTextModelProvider = RecordingTextModelProvider(mutableListOf()),
                textInferenceEngineFactory = LocalTextInferenceEngineFactory { inferenceEngine }
            )
            val operationContext = operationContext("operation-cancel")
            val polishing = async(Dispatchers.Default) {
                polisher.polish(operationContext, request())
            }

            generationStarted.await()
            val acknowledgement = async(Dispatchers.Default) {
                polisher.cancel(operationContext.operationIdentifier)
            }.await()

            assertEquals(operationContext.operationIdentifier, acknowledgement.operationIdentifier)
            assertTrue(inferenceEngine.cancellationRequested)
            assertFailsWith<CancellationException> {
                polishing.await()
            }
            polisher.close()
        }
    }

    @Test
    fun `close cancels and drains a concurrent operation before disposing native resources`()
    {
        runBlocking {
            val lifecycleEvents = mutableListOf<String>()
            val generationStarted = CompletableDeferred<Unit>()
            val generationMayFinish = CompletableDeferred<Unit>()
            val cancellationObserved = CompletableDeferred<Unit>()
            val inferenceEngine = RecordingTextInferenceEngine(
                lifecycleEvents = lifecycleEvents,
                generatedTokens = emptyList(),
                generationStarted = generationStarted,
                generationMayFinish = generationMayFinish,
                cancellationObserved = cancellationObserved,
                completeGenerationOnCancellation = false
            )
            val polisher = AndroidQwenTranscriptPolisher(
                verifiedTextModelProvider = RecordingTextModelProvider(lifecycleEvents),
                textInferenceEngineFactory = LocalTextInferenceEngineFactory { inferenceEngine }
            )
            val polishing = async(Dispatchers.Default) {
                polisher.polish(operationContext("operation-close"), request())
            }

            generationStarted.await()
            val closing = async(Dispatchers.IO) {
                polisher.close()
            }
            cancellationObserved.await()

            assertTrue(!closing.isCompleted)
            assertTrue("engine-closed" !in lifecycleEvents)

            generationMayFinish.complete(Unit)
            assertFailsWith<CancellationException> {
                polishing.await()
            }
            closing.await()

            assertEquals(
                listOf("engine-created", "lease-acquired", "engine-closed", "lease-closed"),
                lifecycleEvents
            )
        }
    }

    @Test
    fun `conservative release unloads Qwen and permits verified reload on the same engine`()
    {
        runBlocking {
            val lifecycleEvents = mutableListOf<String>()
            val modelProvider = RecordingTextModelProvider(lifecycleEvents)
            lateinit var inferenceEngine: RecordingTextInferenceEngine
            val polisher = AndroidQwenTranscriptPolisher(
                verifiedTextModelProvider = modelProvider,
                textInferenceEngineFactory = LocalTextInferenceEngineFactory {
                    RecordingTextInferenceEngine(
                        lifecycleEvents = lifecycleEvents,
                        generatedTokens = listOf("Edited.")
                    ).also { createdEngine ->
                        inferenceEngine = createdEngine
                    }
                }
            )

            assertEquals("Edited.", polisher.polish(operationContext("operation-one"), request()))
            assertEquals(IdleTextModelReleaseResult.RELEASED, polisher.releaseModelIfIdle())
            assertEquals(1, inferenceEngine.unloadCount)
            assertEquals("Edited.", polisher.polish(operationContext("operation-two"), request()))
            assertEquals(2, modelProvider.acquisitionCount)
            assertEquals(2, inferenceEngine.loadedModelPaths.size)

            polisher.close()
            assertEquals(
                listOf(
                    "lease-acquired",
                    "engine-created",
                    "engine-unloaded",
                    "lease-closed",
                    "lease-acquired",
                    "engine-closed",
                    "lease-closed"
                ),
                lifecycleEvents
            )
        }
    }

    @Test
    fun `severe thermal constraint reduces the next model load to two threads`()
    {
        runBlocking {
            val inferenceEngine = RecordingTextInferenceEngine(
                lifecycleEvents = mutableListOf(),
                generatedTokens = listOf("Edited.")
            )
            val polisher = AndroidQwenTranscriptPolisher(
                verifiedTextModelProvider = RecordingTextModelProvider(mutableListOf()),
                textInferenceEngineFactory = LocalTextInferenceEngineFactory { inferenceEngine }
            )

            polisher.setThermalConstrained(true)
            polisher.polish(operationContext("operation-thermal"), request())

            assertEquals(2, inferenceEngine.loadedConfigurations.single().threadCount)
            polisher.close()
        }
    }

    private fun request(): TranscriptPolishingRequest
    {
        return TranscriptPolishingRequest(
            untrustedCleanTranscript = "Clear text.",
            systemInstruction = "Edit faithfully.",
            userMessage = "Edit this transcript.",
            configuration = TranscriptPolishingConfiguration(
                contextSizeTokens = 2048,
                maximumGeneratedTokens = 256,
                temperature = 0.0f,
                topP = 1.0f,
                deterministicSeed = 42,
                threadCount = 4
            )
        )
    }

    private fun operationContext(operationIdentifier: String): InferenceOperationContext
    {
        return InferenceOperationContext(
            clientSessionIdentifier = ClientSessionIdentifier("test-client"),
            operationIdentifier = OperationIdentifier(operationIdentifier),
            privacy = OperationPrivacy.PRIVATE
        )
    }
}

private class RecordingTextModelProvider(
    private val lifecycleEvents: MutableList<String>
) : VerifiedTextModelProvider
{
    var acquisitionCount = 0

    override fun acquireVerifiedModel(): VerifiedTextModelLease
    {
        acquisitionCount += 1
        lifecycleEvents += "lease-acquired"
        return object : VerifiedTextModelLease
        {
            override val verifiedModelFilePath = "verified-qwen.gguf"

            override fun close()
            {
                lifecycleEvents += "lease-closed"
            }
        }
    }
}

private class RecordingTextInferenceEngine(
    private val lifecycleEvents: MutableList<String>,
    private val generatedTokens: List<String>,
    private val generationStarted: CompletableDeferred<Unit>? = null,
    private val generationMayFinish: CompletableDeferred<Unit>? = null,
    private val cancellationObserved: CompletableDeferred<Unit>? = null,
    private val completeGenerationOnCancellation: Boolean = true
) : LocalTextInferenceEngine
{
    val loadedModelPaths = mutableListOf<String>()
    val loadedConfigurations = mutableListOf<TranscriptPolishingConfiguration>()
    val systemPrompts = mutableListOf<String>()
    val userPrompts = mutableListOf<String>()
    var cancellationRequested = false
    var unloadCount = 0

    init
    {
        lifecycleEvents += "engine-created"
    }

    override suspend fun loadModel(modelFilePath: String, configuration: TranscriptPolishingConfiguration)
    {
        loadedModelPaths += modelFilePath
        loadedConfigurations += configuration
    }

    override suspend fun setSystemPrompt(systemInstruction: String)
    {
        systemPrompts += systemInstruction
    }

    override fun generate(userMessage: String, maximumGeneratedTokens: Int): Flow<String>
    {
        userPrompts += userMessage
        return flow {
            generationStarted?.complete(Unit)
            generationMayFinish?.await()
            generatedTokens.forEach { token ->
                emit(token)
            }
        }
    }

    override fun cancelGeneration()
    {
        cancellationRequested = true
        cancellationObserved?.complete(Unit)
        if (completeGenerationOnCancellation)
        {
            generationMayFinish?.complete(Unit)
        }
    }

    override fun unloadModel()
    {
        unloadCount += 1
        lifecycleEvents += "engine-unloaded"
    }

    override fun close()
    {
        lifecycleEvents += "engine-closed"
    }
}
