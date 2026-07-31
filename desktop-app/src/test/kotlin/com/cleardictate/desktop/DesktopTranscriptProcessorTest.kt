package com.cleardictate.desktop

import com.cleardictate.domain.TranscriptMode
import com.cleardictate.domain.TranscriptPolisher
import com.cleardictate.domain.TranscriptPolishingRequest
import com.cleardictate.inference.CancellationAcknowledgement
import com.cleardictate.inference.InferenceOperationContext
import com.cleardictate.inference.OperationIdentifier
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Verifies that expensive model ownership remains lazy and reusable across utterances.
 */
class DesktopTranscriptProcessorTest
{
    @Test
    fun `raw and clean modes do not start the native model worker`() = runTest {
        val workerFactory = RecordingWorkerFactory()
        val processor = DesktopTranscriptProcessor(readyConfiguration(), workerFactory)

        val rawResult = processor.process("  exact   text  ", TranscriptMode.RAW)
        val cleanResult = processor.process("um, send it", TranscriptMode.CLEAN)

        assertEquals("exact text", rawResult.selectedTranscript)
        assertEquals("Send it", cleanResult.selectedTranscript)
        assertEquals(0, workerFactory.createdWorkerCount)
    }

    @Test
    fun `polished requests reuse one worker until an explicit restart`() = runTest {
        val workerFactory = RecordingWorkerFactory()
        val processor = DesktopTranscriptProcessor(readyConfiguration(), workerFactory)

        val firstResult = processor.process("Keep this.", TranscriptMode.POLISHED)
        val secondResult = processor.process("Keep that.", TranscriptMode.POLISHED)
        processor.restartWorker()
        val thirdResult = processor.process("Keep both.", TranscriptMode.POLISHED)

        assertFalse(firstResult.usedDeterministicFallback)
        assertFalse(secondResult.usedDeterministicFallback)
        assertFalse(thirdResult.usedDeterministicFallback)
        assertEquals(2, workerFactory.createdWorkerCount)
        assertTrue(workerFactory.createdWorkers.first().closed)
        assertFalse(workerFactory.createdWorkers.last().closed)

        processor.close()
        assertTrue(workerFactory.createdWorkers.last().closed)
    }

    @Test
    fun `empty and over-context polished requests do not start the worker`() = runTest {
        val workerFactory = RecordingWorkerFactory()
        val processor = DesktopTranscriptProcessor(readyConfiguration(), workerFactory)

        val emptyResult = processor.process("", TranscriptMode.POLISHED)
        val oversizedResult = processor.process("word ".repeat(1_100), TranscriptMode.POLISHED)

        assertTrue(emptyResult.usedDeterministicFallback)
        assertTrue(oversizedResult.usedDeterministicFallback)
        assertEquals(0, workerFactory.createdWorkerCount)
    }

    @Test
    fun `inference failure discards worker so the next request can recover`() = runTest {
        val workerFactory = RecordingWorkerFactory(failFirstWorker = true)
        val processor = DesktopTranscriptProcessor(readyConfiguration(), workerFactory)

        val failedResult = processor.process("Keep this.", TranscriptMode.POLISHED)
        val recoveredResult = processor.process("Keep that.", TranscriptMode.POLISHED)

        assertTrue(failedResult.usedDeterministicFallback)
        assertTrue(workerFactory.createdWorkers.first().closed)
        assertFalse(recoveredResult.usedDeterministicFallback)
        assertEquals(2, workerFactory.createdWorkerCount)
    }

    @Test
    fun `close during worker startup closes the late worker and prevents future processing`() = runTest {
        val workerFactory = SuspendedWorkerFactory()
        val processor = DesktopTranscriptProcessor(readyConfiguration(), workerFactory)
        val processingResult = async {
            runCatching {
                processor.process("Keep this.", TranscriptMode.POLISHED)
            }
        }
        workerFactory.startEntered.await()

        processor.close()
        workerFactory.allowStartToComplete.complete(Unit)
        val failure = processingResult.await().exceptionOrNull()

        assertIsClosedFailure(failure)
        assertTrue(workerFactory.startedWorker.closed)
        assertFailsWith<IllegalStateException> {
            processor.process("Do not restart.", TranscriptMode.CLEAN)
        }
    }

    private fun readyConfiguration(): DesktopRuntimeConfiguration
    {
        return DesktopRuntimeConfiguration(
            workerExecutable = Path.of("C:/ClearDictate/clear_dictate_worker.exe"),
            speechWorkerExecutable = Path.of("C:/ClearDictate/clear_dictate_speech_worker.exe"),
            audioDeviceEnumeratorExecutable = Path.of("C:/ClearDictate/clear_dictate_audio_device_enumerator.exe"),
            workerLauncherExecutable = Path.of("C:/ClearDictate/clear_dictate_worker_launcher.exe"),
            modelPath = Path.of("C:/ClearDictate/qwen.gguf"),
            speechModelDirectory = Path.of("C:/ClearDictate/moonshine")
        )
    }

    private fun assertIsClosedFailure(failure: Throwable?)
    {
        assertTrue(failure is IllegalStateException)
        assertEquals("The desktop transcript processor is closed.", failure.message)
    }

    private class RecordingWorkerFactory(
        private val failFirstWorker: Boolean = false
    ) : DesktopTextWorkerFactory
    {
        val createdWorkers = mutableListOf<RecordingWorker>()

        val createdWorkerCount: Int
            get() = createdWorkers.size

        override suspend fun start(configuration: DesktopRuntimeConfiguration): DesktopTextWorker
        {
            return RecordingWorker(failPolishing = failFirstWorker && createdWorkers.isEmpty()).also(createdWorkers::add)
        }
    }

    private class SuspendedWorkerFactory : DesktopTextWorkerFactory
    {
        val startEntered = CompletableDeferred<Unit>()
        val allowStartToComplete = CompletableDeferred<Unit>()
        val startedWorker = RecordingWorker()

        override suspend fun start(configuration: DesktopRuntimeConfiguration): DesktopTextWorker
        {
            startEntered.complete(Unit)
            allowStartToComplete.await()
            return startedWorker
        }
    }

    private class RecordingWorker(
        private val failPolishing: Boolean = false
    ) : DesktopTextWorker
    {
        var closed = false
            private set

        override suspend fun polish(operationContext: InferenceOperationContext, request: TranscriptPolishingRequest): String
        {
            if (failPolishing)
            {
                throw com.cleardictate.inference.LocalInferenceException(
                    com.cleardictate.inference.InferenceFailureCategory.PROCESS_DIED
                )
            }
            return request.untrustedCleanTranscript
        }

        override suspend fun cancel(operationIdentifier: OperationIdentifier): CancellationAcknowledgement
        {
            return CancellationAcknowledgement(operationIdentifier)
        }

        override fun close()
        {
            closed = true
        }
    }
}
