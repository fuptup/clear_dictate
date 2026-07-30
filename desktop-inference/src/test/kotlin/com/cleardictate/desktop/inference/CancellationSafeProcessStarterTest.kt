package com.cleardictate.desktop.inference

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Exercises cancellation after operating-system process creation but before ownership delivery.
 */
class CancellationSafeProcessStarterTest
{
    @Test
    fun `cancellation during process creation terminates process that cannot be handed off`() = runBlocking {
        val processCreationExecutor = Executors.newSingleThreadExecutor()
        val processCreationDispatcher = processCreationExecutor.asCoroutineDispatcher()
        val processCreated = CountDownLatch(1)
        val allowStartOperationToReturn = CountDownLatch(1)
        val fakeProcess = RecordingProcess()

        try
        {
            val starter = CancellationSafeProcessStarter(processCreationDispatcher)
            val startResult = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching {
                    starter.start {
                        processCreated.countDown()
                        check(allowStartOperationToReturn.await(5, TimeUnit.SECONDS))
                        fakeProcess
                    }
                }
            }

            assertTrue(processCreated.await(5, TimeUnit.SECONDS))
            startResult.cancel()
            allowStartOperationToReturn.countDown()
            startResult.join()

            assertTrue(fakeProcess.terminated.await(5, TimeUnit.SECONDS))
        }
        finally
        {
            processCreationDispatcher.close()
            processCreationExecutor.shutdownNow()
        }
    }

    private class RecordingProcess : Process()
    {
        val terminated = CountDownLatch(1)

        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int = 0
        override fun exitValue(): Int = if (terminated.count == 0L) 0 else throw IllegalThreadStateException()

        override fun destroy()
        {
            terminated.countDown()
        }

        override fun destroyForcibly(): Process
        {
            terminated.countDown()
            return this
        }
    }
}
