package com.cleardictate.desktop.inference

import com.cleardictate.domain.TranscriptMode
import com.cleardictate.domain.TranscriptFallbackReason
import com.cleardictate.domain.TranscriptPolishingPromptBuilder
import com.cleardictate.domain.TranscriptProcessingPipeline
import com.cleardictate.inference.ClientSessionIdentifier
import com.cleardictate.inference.InferenceOperationContext
import com.cleardictate.inference.InferenceFailureCategory
import com.cleardictate.inference.LocalInferenceException
import com.cleardictate.inference.OperationIdentifier
import com.cleardictate.inference.OperationPrivacy
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WindowsTextWorkerClientIntegrationTest
{
    @Test
    fun `Kotlin host completes a real polish request through the native worker`()
    {
        val workerExecutable = System.getProperty(WORKER_EXECUTABLE_PROPERTY)
        val textModel = System.getProperty(TEXT_MODEL_PROPERTY)
        assumeTrue(
            workerExecutable != null && textModel != null,
            "The native worker and model properties are required for this integration test."
        )

        runBlocking {
            WindowsTextWorkerClient.start(
                WindowsTextWorkerConfiguration(
                    workerExecutable = Path.of(requireNotNull(workerExecutable)),
                    modelPath = Path.of(requireNotNull(textModel)),
                    inferenceThreadCount = 4
                )
            ).use { client ->
                val operationContext = InferenceOperationContext(
                    clientSessionIdentifier = ClientSessionIdentifier("integration_session"),
                    operationIdentifier = OperationIdentifier("integration_operation"),
                    privacy = OperationPrivacy.PRIVATE
                )
                val pipeline = TranscriptProcessingPipeline(
                    polisher = client,
                    polishingTimeoutMilliseconds = 60_000
                )
                val emptyCleanTranscript = pipeline.process(
                    operationContext = operationContext.copy(
                        operationIdentifier = OperationIdentifier("empty_operation")
                    ),
                    exactRawTranscript = "um",
                    mode = TranscriptMode.POLISHED
                )
                assertTrue(emptyCleanTranscript.usedDeterministicFallback)
                assertEquals(TranscriptFallbackReason.INFERENCE_FAILURE, emptyCleanTranscript.fallbackReason)
                assertTrue(emptyCleanTranscript.selectedTranscript.isEmpty())

                val cancellationContext = operationContext.copy(
                    operationIdentifier = OperationIdentifier("cancel_operation")
                )
                val longPolishingRequest = TranscriptPolishingPromptBuilder.build(
                    List(350) { "keep this sentence exactly" }.joinToString(" ")
                )
                val cancelledGeneration = async(start = CoroutineStart.UNDISPATCHED) {
                    runCatching {
                        client.polish(cancellationContext, longPolishingRequest)
                    }
                }
                val cancellationAcknowledgement = client.cancel(cancellationContext.operationIdentifier)
                assertEquals(cancellationContext.operationIdentifier, cancellationAcknowledgement.operationIdentifier)
                val cancellationFailure = cancelledGeneration.await().exceptionOrNull()
                assertIs<LocalInferenceException>(cancellationFailure)
                assertEquals(InferenceFailureCategory.CANCELLED, cancellationFailure.category)

                val processedTranscript = pipeline.process(
                    operationContext = operationContext,
                    exactRawTranscript = "Um, send build AB12 to port 8080 tomorrow at 14:30, not 14:45.",
                    mode = TranscriptMode.POLISHED
                )
                val result = processedTranscript.selectedTranscript

                assertFalse(processedTranscript.usedDeterministicFallback)
                assertTrue(result.isNotBlank())
                assertContains(result, "AB12")
                assertContains(result, "8080")
                assertContains(result, "14:30")
                assertContains(result, "not")
                assertContains(result, "14:45")
            }
        }
    }

    private companion object
    {
        const val WORKER_EXECUTABLE_PROPERTY = "clearDictate.workerExecutable"
        const val TEXT_MODEL_PROPERTY = "clearDictate.textModel"
    }
}
