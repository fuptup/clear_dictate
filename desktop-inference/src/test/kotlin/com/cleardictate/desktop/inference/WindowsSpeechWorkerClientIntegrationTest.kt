package com.cleardictate.desktop.inference

import com.cleardictate.inference.ClientSessionIdentifier
import com.cleardictate.inference.InferenceOperationContext
import com.cleardictate.inference.OperationIdentifier
import com.cleardictate.inference.OperationPrivacy
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WindowsSpeechWorkerClientIntegrationTest
{
    @Test
    fun `Kotlin host records and finalizes through the real speech worker`()
    {
        val workerExecutable = System.getProperty(WORKER_EXECUTABLE_PROPERTY)
        val speechModelDirectory = System.getProperty(SPEECH_MODEL_DIRECTORY_PROPERTY)
        val liveMicrophoneAllowed = System.getProperty(LIVE_MICROPHONE_PROPERTY) == "true"
        assumeTrue(
            workerExecutable != null && speechModelDirectory != null && liveMicrophoneAllowed,
            "The speech worker, model directory, and explicit live-microphone permission are required."
        )

        runBlocking {
            WindowsSpeechWorkerClient.start(
                WindowsSpeechWorkerConfiguration(
                    workerExecutable = Path.of(requireNotNull(workerExecutable)),
                    modelDirectory = Path.of(requireNotNull(speechModelDirectory))
                )
            ).use { client ->
                val cancelledOperationContext = InferenceOperationContext(
                    clientSessionIdentifier = ClientSessionIdentifier("speech_integration_session"),
                    operationIdentifier = OperationIdentifier("speech_cancel_operation"),
                    privacy = OperationPrivacy.PRIVATE
                )
                val cancelledRecording = client.startRecording(cancelledOperationContext)
                delay(500)
                val cancellationAcknowledgement = client.cancel(cancelledOperationContext.operationIdentifier)
                assertEquals(cancelledOperationContext.operationIdentifier, cancellationAcknowledgement.operationIdentifier)
                assertTrue(cancelledRecording.transcript.value.visibleRawTranscript.isEmpty())

                val operationContext = cancelledOperationContext.copy(
                    operationIdentifier = OperationIdentifier("speech_finalize_operation")
                )
                val recording = client.startRecording(operationContext)
                delay(3_000)
                val finalTranscript = client.stopRecording(operationContext.operationIdentifier)

                assertEquals(operationContext.operationIdentifier, recording.operationIdentifier)
                assertEquals(finalTranscript, recording.transcript.value.visibleRawTranscript)
            }
        }
    }

    private companion object
    {
        const val WORKER_EXECUTABLE_PROPERTY = "clearDictate.speechWorkerExecutable"
        const val SPEECH_MODEL_DIRECTORY_PROPERTY = "clearDictate.speechModelDirectory"
        const val LIVE_MICROPHONE_PROPERTY = "clearDictate.allowLiveMicrophone"
    }
}
